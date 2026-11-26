package com.ccu.addition.feature;

import com.ccu.addition.CcuServer;
import com.ccu.addition.CcuRules;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 矿车加载器。
 *
 * <p>当「指定种类的矿车」停在「指定种类的铁轨」上时，每 5 秒给它所在区块挂一次加载票，
 * 让周围 {@code minecartLoaderRange} 个区块保持加载与实体刻。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *     <li>判定与续票都只发生在每 {@code minecartScanInterval} 刻（默认 100 刻 = 5 秒）的一轮扫描里，
 *     不做逐刻探测：一轮扫描的开销是「实体全量遍历 + 命中矿车各挂一次票」，
 *     而挂一次票要按半径覆盖 {@code (2R+1)²} 个区块，逐刻续票的代价是这里的 20 倍以上。</li>
 *     <li>每轮扫描只要矿车在指定铁轨上、或者正被活塞带动（{@code moving_piston}），就给它续票。</li>
 *     <li>ticket 自带 {@value #TICKET_TIMEOUT_TICKS} 刻超时，且不持久化；每 5 秒重复挂票会刷新超时
 *     （{@code TicketStorage.addTicket} 对同类型同等级的 ticket 调用 {@code resetTicksLeft}），
 *     矿车离开铁轨或消失后票会自然过期，不会留下残留。</li>
 *     <li><b>静止矿车换长票</b>：连续两轮续票时矿车都在同一区块，说明它没在动，
 *     这一轮改用 {@value #IDLE_TICKET_TIMEOUT_TICKS} 刻（30 秒）的票，多留一倍容错。</li>
 *     <li>配合 {@link #RAIL_GRACE_TICKS} 的宽限：车最近一次确认在铁轨上之后 5 秒内，
 *     即使当轮判定不到铁轨，仍继续续票。移动中的矿车靠这条穿越短暂的空档，
 *     也是「5 秒采样」能接住「只有 2 刻在铁轨上」的关键。</li>
 *     <li>矿车移动后旧区块的票不立刻撤销，最多残留一轮票的有效期。</li>
 *     <li>只扫描已加载的实体，不会为了找矿车而强行加载区块；已记下的锚点由保活逻辑续票。</li>
 * </ul>
 */
public final class MinecartChunkLoader {

	/**
	 * 全量扫描间隔的兜底范围，实际值来自规则 {@code minecartScanInterval}。
	 */
	private static final int MIN_SCAN_INTERVAL = 1;
	private static final int MAX_SCAN_INTERVAL = 1200;

	/**
	 * 票据有效期：15 秒（300 刻），是续票间隔的 3 倍。
	 *
	 * <p>留出两轮容错，专为移动中的矿车准备：一轮判定落空（跨方块、上坡、被推离轨道、
	 * 铁轨刚被拆）不会让票过期，下一轮补上就能接住。代价是矿车消失后最多残留 15 秒。</p>
	 */
	private static final int TICKET_TIMEOUT_TICKS = 300;

	/**
	 * 静止矿车的票据有效期：30 秒（600 刻）。
	 *
	 * <p>连续两轮续票时矿车都停在同一区块，说明它不是正在移动的加载器（机器停了、或者这条产线
	 * 一时半会儿不会动），这时换用更长的票：同样的续票频率下多留一倍余量，机器停着的时候
	 * 服务器卡一下、或者某一轮恰好错过判定窗口，都不至于把区块掉下去。</p>
	 */
	private static final int IDLE_TICKET_TIMEOUT_TICKS = 600;

	/**
	 * 铁轨判定的宽限：矿车最近一次被确认停在指定铁轨上之后这么久之内，
	 * 即使当轮判定不成立也继续按「在铁轨上」处理（5 秒 = 100 刻）。
	 */
	private static final int RAIL_GRACE_TICKS = 100;

	/**
	 * 自举宽限：重启后最多等 60 秒让锚点区块加载出来，超时就认为矿车不在了，丢弃锚点。
	 */
	private static final int BOOTSTRAP_GRACE_TICKS = 1200;

	/**
	 * 锚点落盘的节流：两轮之间至少隔 5 秒，避免矿车移动时频繁写文件。
	 */
	private static final int ANCHOR_SAVE_INTERVAL = 100;

	/**
	 * 锚点校验周期：固定每 1200 刻检查一次「区块已加载但矿车没了」的锚点。
	 * 不跟随扫描间隔，避免扫描调密后校验也跟着变频繁。
	 */
	private static final int ANCHOR_VALIDATE_TICKS = 1200;

	/**
	 * 锚点保活窗口：最近 2 分钟（真实时间）内确认过的矿车，即使这一轮判定不到铁轨，
	 * 也按锚点位置继续挂票。机器的活塞推进阶段、区块刚被卸载等情况都靠它兜住。
	 *
	 * <p>用真实时间而不是游戏刻：tick rate 调到 100 时，按刻算的窗口会被压缩 5 倍。</p>
	 */
	private static final long ANCHOR_KEEPALIVE_MS = 120_000L;

	/**
	 * 锚点过期：连续 2 分钟没有被识别（既不在指定铁轨上、也没被活塞带动）就丢弃锚点并落盘。
	 *
	 * <p>判定只在每 {@code minecartScanInterval} 刻的全量扫描里做一次。矿车可能整个推进周期里
	 * 只有 2 刻停在目标铁轨上，5 秒一次的采样本来就会漏几轮，窗口太短会把还在干活的矿车误删；
	 * 两分钟足够跨过几十轮采样，又不会让「铁轨被拆 / 车被推走」的锚点永久留着。</p>
	 */
	private static final long ANCHOR_EXPIRE_MS = 120_000L;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static final TicketType MINECART_TICKET = new TicketType(
			TICKET_TIMEOUT_TICKS,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	/**
	 * 静止矿车用的长票（{@value #IDLE_TICKET_TIMEOUT_TICKS} 刻）。两种票并存不冲突：
	 * {@code TicketStorage} 按「类型 + 等级」去重，旧票到期自然消失。
	 */
	private static final TicketType MINECART_TICKET_IDLE = new TicketType(
			IDLE_TICKET_TIMEOUT_TICKS,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	private static final int MIN_RANGE = 1;
	private static final int MAX_RANGE = 32;

	/**
	 * 诊断心跳：状态没变化时每 60 秒（真实时间）打一行，
	 * 避免开着 debugLog 时刷屏。状态变化（命中数变了）则立即输出。
	 */
	private static final long HEARTBEAT_MS = 60_000L;

	/**
	 * 诊断信息里最多列几个样例（续票位置 / 未命中原因），防止一次几十辆车刷屏。
	 */
	private static final int MAX_SAMPLES = 3;

	/**
	 * 原版 {@code ChunkLevel.byStatus(FullChunkStatus.FULL)}，即默认满区块的 ticket 等级。
	 * 换算关系：ticket 等级 = 本值 - ticket 半径。
	 */
	private static final int FULL_CHUNK_LEVEL = 33;

	/**
	 * 上一轮的命中数，用来判断状态是否变化；-1 表示还没跑过。
	 */
	private static int lastMatched = -1;

	/**
	 * 上一轮的类型命中数。矿车出现/消失但没挂上票（比如停错铁轨）同样值得输出一条，
	 * 所以它也参与「状态变化」判断。
	 */
	private static int lastTypeHits = -1;

	/**
	 * 上一轮靠宽限判定才续上票的车辆数，同样参与「状态变化」判断。
	 */
	private static int lastGraceHits = -1;

	/**
	 * 上一轮「被活塞带动」的车辆数，同样参与「状态变化」判断。
	 */
	private static int lastPistonHits = -1;

	/**
	 * 上一轮需要补票重新加载的锚点块数，用来只在发生变化时打一行日志。
	 */
	private static int lastKeepAlive = -1;

	/**
	 * 上次心跳输出的时刻（毫秒），-1 表示还没输出过。
	 */
	private static long lastHeartbeatMs = -1;

	/**
	 * 矿车 UUID -> 最近一次确认停在指定铁轨上的游戏刻，供 {@link #RAIL_GRACE_TICKS} 宽限判定。
	 */
	private static final Map<UUID, Integer> LAST_ON_RAIL = new HashMap<>();

	/**
	 * 矿车锚点：UUID -> 最近一次成功续票时所处的维度与区块。
	 *
	 * <p>只靠扫描「已加载区块里的实体」有个死角：服务器重启后票全丢了，矿车所在区块没加载，
	 * 实体也就扫不到，加载器会一直空转。所以把成功续票的矿车记下来，重启后先按记录把区块加载起来，
	 * 把矿车「捞」回世界，之后就和正常流程一样了。</p>
	 */
	private static final Map<UUID, Anchor> ANCHORS = new HashMap<>();

	/**
	 * 还没被重新看到的锚点，自举阶段逐个尝试加载区块。
	 */
	private static final Set<UUID> BOOTSTRAPPING = new HashSet<>();

	/**
	 * 自举截止时刻（游戏刻），-1 表示还没开始。
	 */
	private static int bootstrapDeadline = -1;

	private static boolean anchorsDirty = false;
	private static int lastAnchorSave = -1;

	/**
	 * 矿车 UUID -> 最近一次成功续票的时刻（毫秒时间戳），供保活与过期判断。
	 */
	private static final Map<UUID, Long> LAST_MATCHED = new HashMap<>();

	/**
	 * 矿车 UUID -> 上一次续票时所在的区块，用来判断矿车这两轮有没有动。
	 */
	private static final Map<UUID, ChunkPos> LAST_GRANT_POS = new HashMap<>();

	/**
	 * 当前正在用 30 秒长票的矿车，仅用于「长票 / 短票」切换时打一行日志。
	 */
	private static final Set<UUID> IDLE = new HashSet<>();

	/**
	 * 预设规则 -> 结果缓存。tick 每刻都会跑，不能每次都重新分配 Set。
	 */
	private static CcuRules.CartPreset cachedTypePreset;
	private static CcuRules.RailPreset cachedRailPreset;
	private static Set<String> cachedTypes = Set.of();
	private static Set<String> cachedRails = Set.of();

	/**
	 * 锚点记录：维度用 id 字符串保存，避免跨版本（1.21.10 的 ResourceLocation / 1.21.11 的 Identifier）差异。
	 */
	private record Anchor(String dimensionId, ChunkPos pos) {
	}

	private MinecartChunkLoader() {
	}

	/**
	 * 带缓存的 {@code minecartLoaderTypes}：预设值不变时直接复用。
	 */
	private static Set<String> types() {
		CcuRules.CartPreset preset = CcuRules.minecartLoaderTypes;
		if (preset != cachedTypePreset) {
			cachedTypePreset = preset;
			cachedTypes = Set.of(preset.id());
		}
		return cachedTypes;
	}

	/**
	 * 带缓存的 {@code minecartLoaderRails}：预设值不变时直接复用。
	 */
	private static Set<String> rails() {
		CcuRules.RailPreset preset = CcuRules.minecartLoaderRails;
		if (preset != cachedRailPreset) {
			cachedRailPreset = preset;
			cachedRails = Set.of(preset.id());
		}
		return cachedRails;
	}

	/**
	 * @return 全量扫描间隔（刻），由规则 {@code minecartScanInterval} 提供并做范围兜底
	 */
	private static int scanInterval() {
		return Math.max(MIN_SCAN_INTERVAL, Math.min(MAX_SCAN_INTERVAL, CcuRules.minecartScanInterval));
	}

	public static void tick(MinecraftServer server) {
		if (!CcuRules.minecartChunkLoader) {
			return;
		}

		Set<String> types = types();
		Set<String> rails = rails();
		int range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, CcuRules.minecartLoaderRange));
		int ticketRadius = range + 2;
		int now = server.getTickCount();
		long nowMs = System.currentTimeMillis();

		// 全量扫描：发现新矿车、判定铁轨、续票，一并出诊断（间隔由 minecartScanInterval 控制，默认 100 刻）
		if (now % scanInterval() != 0) {
			return;
		}
		if (types.isEmpty() || rails.isEmpty()) {
			CcuServer.debug("矿车加载器：配置解析后为空，本轮跳过（minecartLoaderTypes=%s，minecartLoaderRails=%s）",
					types, rails);
			return;
		}

		// 先把上次记下来的锚点区块加载起来（重启/卸载后自举），再走正常扫描
		updateAnchors(server, now, nowMs, ticketRadius);

		int scanned = 0;
		int typeHits = 0;
		int matched = 0;
		int graceHits = 0;
		int pistonHits = 0;
		List<String> hitPositions = new ArrayList<>();
		List<String> misses = new ArrayList<>();

		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				scanned++;
				if (entity.isRemoved()) {
					continue;
				}
				// 只按注册表 id 判断种类，不依赖 AbstractMinecart 类（1.21.10 与 1.21.11 的包名不同）
				if (!types.contains(idOfEntity(entity))) {
					continue;
				}
				typeHits++;

				UUID cartId = entity.getUUID();
				boolean onRail = isOnRail(level, entity, rails);
				if (onRail) {
					LAST_ON_RAIL.put(cartId, now);
				} else {
					// 这一轮判定不到铁轨：可能是短暂的「悬空」（跨方块、上坡、被推离），
					// 也可能整台机器正被活塞推着走——此时方块会临时变成 moving_piston。
					// 前者靠宽限撑过去，后者按「已被认定的加载器矿车」继续续票。
					Integer lastSeen = LAST_ON_RAIL.get(cartId);
					boolean withinGrace = lastSeen != null && now - lastSeen <= RAIL_GRACE_TICKS;
					boolean carriedByPiston = !withinGrace
							&& ANCHORS.containsKey(cartId)
							&& isOnMovingPiston(level, entity);
					if (!withinGrace && !carriedByPiston) {
						// 类型对了但不在配置的铁轨上——最常见的原因就是铁轨 id 写错或漏配
						if (CcuRules.debugLog && misses.size() < MAX_SAMPLES) {
							misses.add(describe(level, entity));
						}
						continue;
					}
					if (withinGrace) {
						graceHits++;
					} else {
						// 被活塞带动同样算一次确认，宽限从这一轮重新计时
						LAST_ON_RAIL.put(cartId, now);
						pistonHits++;
					}
				}

				// +2：把「半径 R 内实体刻」换算成 addTicketWithRadius 需要的 ticket 半径
				grantTicket(level, cartId, entity.chunkPosition(), ticketRadius);
				matched++;
				recordAnchor(level, entity, nowMs);
				if (CcuRules.debugLog && hitPositions.size() < MAX_SAMPLES) {
					String where = entity.blockPosition().toShortString();
					if (!hitPositions.contains(where)) {
						hitPositions.add(where);
					}
				}
			}
		}

		// 清理长期没再出现过的矿车（宽限早已失效，留着没用），避免映射随矿车数量无限增长
		LAST_ON_RAIL.entrySet().removeIf(entry -> now - entry.getValue() > RAIL_GRACE_TICKS * 2);
		LAST_GRANT_POS.keySet().removeIf(id -> !ANCHORS.containsKey(id));
		IDLE.retainAll(ANCHORS.keySet());

		// 过期判定放在扫描之后：本轮刚匹配上的车不会被误删
		expireAnchors(nowMs);
		saveAnchorsIfDue(now);

		if (!shouldLog(typeHits, matched, graceHits, pistonHits, nowMs)) {
			return;
		}

		if (typeHits == 0) {
			CcuServer.debug("矿车加载器：扫描 %d 个实体，没有类型命中，未续票（minecartLoaderTypes=%s）。"
							+ "注意只扫描已加载区块里的实体——服务器重启后、或没人靠近时，矿车所在区块不会被加载，也就扫不到",
					scanned, types);
			return;
		}

		if (matched > 0 && (graceHits > 0 || pistonHits > 0)) {
			CcuServer.debug("矿车加载器：扫描 %d 个实体，类型命中 %d，铁轨命中 %d 并已续票"
							+ "（其中 %d 辆靠宽限判定、%d 辆正被活塞带动）"
							+ "（半径 %d 区块 → ticket 半径 %d / 等级 %d）；续票位置：%s",
					scanned, typeHits, matched, graceHits, pistonHits, range, ticketRadius,
					FULL_CHUNK_LEVEL - ticketRadius, joinSamples(hitPositions));
		} else if (matched > 0) {
			CcuServer.debug("矿车加载器：扫描 %d 个实体，类型命中 %d，铁轨命中 %d 并已续票"
							+ "（半径 %d 区块 → ticket 半径 %d / 等级 %d）；续票位置：%s",
					scanned, typeHits, matched, range, ticketRadius,
					FULL_CHUNK_LEVEL - ticketRadius, joinSamples(hitPositions));
		} else {
			CcuServer.debug("矿车加载器：扫描 %d 个实体，类型命中 %d，但没有一辆停在指定铁轨上，未续票"
							+ "（minecartLoaderRails=%s）",
					scanned, typeHits, rails);
		}
		if (!misses.isEmpty()) {
			CcuServer.debug("矿车加载器：其中 %d 辆不在指定铁轨上，样本：%s",
					typeHits - matched, joinSamples(misses));
		}
	}

	/**
	 * 诊断输出节流：命中数变化立刻输出，否则每 {@link #HEARTBEAT_CYCLES} 轮输出一次。
	 */
	private static boolean shouldLog(int typeHits, int matched, int graceHits, int pistonHits, long nowMs) {
		if (!CcuRules.debugLog) {
			// 关着的时候不维护状态：这样重新打开 debugLog 后第一轮就会立刻输出一次
			lastMatched = -1;
			lastTypeHits = -1;
			lastGraceHits = -1;
			lastPistonHits = -1;
			lastHeartbeatMs = -1;
			return false;
		}
		boolean changed = matched != lastMatched || typeHits != lastTypeHits
				|| graceHits != lastGraceHits || pistonHits != lastPistonHits;
		lastMatched = matched;
		lastTypeHits = typeHits;
		lastGraceHits = graceHits;
		lastPistonHits = pistonHits;
		if (changed) {
			lastHeartbeatMs = nowMs;
			return true;
		}
		if (lastHeartbeatMs < 0 || nowMs - lastHeartbeatMs >= HEARTBEAT_MS) {
			lastHeartbeatMs = nowMs;
			return true;
		}
		return false;
	}

	private static String joinSamples(List<String> samples) {
		if (samples.isEmpty()) {
			return "（无）";
		}
		String joined = String.join("，", samples);
		// 只收满了 MAX_SAMPLES 才可能是「还有更多」，此时加省略
		return samples.size() >= MAX_SAMPLES ? joined + " 等" : joined;
	}

	/**
	 * 描述一个「类型命中但铁轨不命中」的矿车：位置 + 所在方块与下方方块，
	 * 便于对照配置判断是铁轨 id 写错还是矿车确实没停在铁轨上。
	 */
	private static String describe(ServerLevel level, Entity entity) {
		BlockPos pos = entity.blockPosition();
		return idOfEntity(entity) + " @ " + pos.toShortString()
				+ " 所在=" + blockId(level, pos)
				+ " 下方=" + blockId(level, pos.below());
	}

	private static String blockId(ServerLevel level, BlockPos pos) {
		return normalize(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
	}

	// ---------------------------------------------------------------- 锚点持久化与自举

	private static Path anchorsPath() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("carpet-ccu-addition").resolve("minecart-anchors.json");
	}

	/**
	 * 读取上次记录下来的锚点，全部标记为「待自举」，等 tick 里按维度逐个尝试加载。
	 */
	public static void loadAnchors() {
		ANCHORS.clear();
		BOOTSTRAPPING.clear();
		LAST_MATCHED.clear();
		bootstrapDeadline = -1;
		anchorsDirty = false;

		Path path = anchorsPath();
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> entry : json.entrySet()) {
				try {
					JsonObject value = entry.getValue().getAsJsonObject();
					UUID id = UUID.fromString(entry.getKey());
					ANCHORS.put(id, new Anchor(
							value.get("dimension").getAsString(),
							new ChunkPos(value.get("chunkX").getAsInt(), value.get("chunkZ").getAsInt())));
					BOOTSTRAPPING.add(id);
				} catch (Exception e) {
					CcuServer.LOGGER.warn("跳过一条无法解析的矿车锚点（{}）：{}", entry.getKey(), e.toString());
				}
			}
			if (!ANCHORS.isEmpty()) {
				CcuServer.LOGGER.info("矿车加载器：读取到 {} 条矿车锚点，将在启用后尝试重新加载", ANCHORS.size());
			}
		} catch (Exception e) {
			CcuServer.LOGGER.warn("读取矿车加载器锚点失败：{}", path, e);
		}
	}

	/**
	 * 关服时强制落盘（供 {@code CcuServer#onServerClosed} 调用）。
	 */
	public static void saveAnchors() {
		saveAnchors(0);
	}

	private static void saveAnchorsIfDue(int now) {
		if (!anchorsDirty || (lastAnchorSave >= 0 && now - lastAnchorSave < ANCHOR_SAVE_INTERVAL)) {
			return;
		}
		saveAnchors(now);
	}

	private static void saveAnchors(int now) {
		if (!anchorsDirty) {
			return;
		}
		anchorsDirty = false;
		lastAnchorSave = now;

		Path path = anchorsPath();
		try {
			Files.createDirectories(path.getParent());
			JsonObject json = new JsonObject();
			ANCHORS.forEach((id, anchor) -> {
				JsonObject entry = new JsonObject();
				entry.addProperty("dimension", anchor.dimensionId());
				entry.addProperty("chunkX", anchor.pos().x);
				entry.addProperty("chunkZ", anchor.pos().z);
				json.add(id.toString(), entry);
			});
			Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
		} catch (Exception e) {
			// 写失败就把 dirty 立回去，下个周期再试
			anchorsDirty = true;
			CcuServer.LOGGER.warn("保存矿车加载器锚点失败：{}", path, e);
		}
	}

	/**
	 * 自举：把锚点记录的区块加载出来，让矿车实体重新进入世界。
	 *
	 * <p>区块真正加载出来后就能判断矿车还在不在：在就交给正常扫描接管，不在就丢掉这条锚点。
	 * 等待超过 {@link #BOOTSTRAP_GRACE_TICKS} 还加载不出来的也丢掉，避免永久吊着一块区块。</p>
	 */
	private static void updateAnchors(MinecraftServer server, int now, long nowMs, int ticketRadius) {
		if (bootstrapDeadline < 0) {
			bootstrapDeadline = now + BOOTSTRAP_GRACE_TICKS;
		}

		if (!BOOTSTRAPPING.isEmpty()) {
			Iterator<UUID> iterator = BOOTSTRAPPING.iterator();
			while (iterator.hasNext()) {
				UUID id = iterator.next();
				Anchor anchor = ANCHORS.get(id);
				if (anchor == null) {
					iterator.remove();
					continue;
				}
				ServerLevel level = findLevel(server, anchor.dimensionId());
				if (level == null) {
					// 维度还没加载好，下个周期再试
					continue;
				}
				if (level.getChunkSource().getChunkNow(anchor.pos().x, anchor.pos().z) != null) {
					if (level.getEntity(id) != null) {
						CcuServer.debug("矿车加载器：锚点已找回矿车 %s @ %s", id, anchor.pos());
					} else {
						CcuServer.debug("矿车加载器：锚点区块已加载但没有这辆矿车，丢弃锚点 %s @ %s", id, anchor.pos());
						ANCHORS.remove(id);
						LAST_MATCHED.remove(id);
						LAST_GRANT_POS.remove(id);
						anchorsDirty = true;
					}
					iterator.remove();
					continue;
				}
				if (now > bootstrapDeadline) {
					CcuServer.debug("矿车加载器：等了 %d 刻仍未加载出锚点区块 %s，丢弃锚点",
							BOOTSTRAP_GRACE_TICKS, anchor.pos());
					ANCHORS.remove(id);
					LAST_MATCHED.remove(id);
					anchorsDirty = true;
					iterator.remove();
					continue;
				}
				// 先挂票把区块拉起来，下一轮（或下下轮）就能看到矿车
				grantTicket(level, id, anchor.pos(), ticketRadius);
			}
		}

		// 锚点保活：最近 ANCHOR_KEEPALIVE_TICKS 内确认过的矿车，按锚点位置继续挂票。
		// 这样即使某几轮判定不到铁轨（机器正被活塞推着走、区块刚被卸载），票也不会断，
		// 避免「断票 -> 区块卸载 -> 矿车实体一起被卸载 -> 再也扫不到」的连锁。
		int keepAlive = 0;
		for (Map.Entry<UUID, Anchor> entry : ANCHORS.entrySet()) {
			if (BOOTSTRAPPING.contains(entry.getKey())) {
				continue;
			}
			Long lastMatch = LAST_MATCHED.get(entry.getKey());
			if (lastMatch == null || nowMs - lastMatch > ANCHOR_KEEPALIVE_MS) {
				continue;
			}
			ServerLevel level = findLevel(server, entry.getValue().dimensionId());
			if (level == null) {
				continue;
			}
			grantTicket(level, entry.getKey(), entry.getValue().pos(), ticketRadius);
			keepAlive++;
		}
		if (keepAlive > 0 && keepAlive != lastKeepAlive) {
			CcuServer.debug("矿车加载器：%d 个锚点按记忆维持加载", keepAlive);
		}
		lastKeepAlive = keepAlive;

		// 定期校验：区块已加载却找不到对应矿车的锚点，清掉，避免配置文件无限增长
		if (now % ANCHOR_VALIDATE_TICKS == 0) {
			boolean removedAny = false;
			Iterator<Map.Entry<UUID, Anchor>> iterator = ANCHORS.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<UUID, Anchor> entry = iterator.next();
				if (BOOTSTRAPPING.contains(entry.getKey())) {
					continue;
				}
				ServerLevel level = findLevel(server, entry.getValue().dimensionId());
				if (level == null) {
					continue;
				}
				ChunkPos pos = entry.getValue().pos();
				if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
					// 区块没加载，无法判断矿车是否还在，先留着
					continue;
				}
				if (level.getEntity(entry.getKey()) == null) {
					LAST_MATCHED.remove(entry.getKey());
					LAST_GRANT_POS.remove(entry.getKey());
					IDLE.remove(entry.getKey());
					iterator.remove();
					removedAny = true;
				}
			}
			if (removedAny) {
				anchorsDirty = true;
			}
		}
	}

	/**
	 * 锚点过期：连续 {@link #ANCHOR_EXPIRE_MS} 没有被识别的锚点丢弃并落盘。
	 *
	 * <p>只处理已经被观察到过的锚点；重启后刚读回来的锚点先开始计时，留出被认领的机会。</p>
	 */
	private static void expireAnchors(long nowMs) {
		boolean removed = false;
		Iterator<Map.Entry<UUID, Anchor>> iterator = ANCHORS.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Anchor> entry = iterator.next();
			if (BOOTSTRAPPING.contains(entry.getKey())) {
				// 自举中：还没被观察到，不参与过期判定
				continue;
			}
			Long lastMatch = LAST_MATCHED.get(entry.getKey());
			if (lastMatch == null) {
				// 重启后刚读回来：从现在开始计时
				LAST_MATCHED.put(entry.getKey(), nowMs);
				continue;
			}
			if (nowMs - lastMatch < ANCHOR_EXPIRE_MS) {
				continue;
			}
			CcuServer.debug("矿车加载器：矿车 %s 已连续 %d 秒没有被识别（不在指定铁轨上、也没被活塞带动），丢弃锚点 @ %s",
					entry.getKey(), ANCHOR_EXPIRE_MS / 1000, entry.getValue().pos());
			LAST_ON_RAIL.remove(entry.getKey());
			LAST_MATCHED.remove(entry.getKey());
			LAST_GRANT_POS.remove(entry.getKey());
			IDLE.remove(entry.getKey());
			iterator.remove();
			removed = true;
		}
		if (removed) {
			anchorsDirty = true;
		}
	}

	/**
	 * 给一辆矿车续票：本轮位置与上一轮相同就换用 30 秒的长票，否则用默认的 15 秒票。
	 *
	 * <p>「同一位置」按区块算而不是按精确坐标：矿车停在原地时可能因为被挤压、贴轨而
	 * 抖动零点几个方块，按坐标比会被当成「在动」，按区块比才稳定。</p>
	 */
	private static void grantTicket(ServerLevel level, UUID cartId, ChunkPos pos, int ticketRadius) {
		ChunkPos previous = LAST_GRANT_POS.put(cartId, pos);
		boolean idle = pos.equals(previous);
		if (idle != IDLE.contains(cartId)) {
			if (idle) {
				IDLE.add(cartId);
				CcuServer.debug("矿车加载器：矿车 %s 连续两轮没动，本轮改用 %d 秒长票 @ %s",
						cartId, IDLE_TICKET_TIMEOUT_TICKS / 20, pos);
			} else {
				IDLE.remove(cartId);
				CcuServer.debug("矿车加载器：矿车 %s 又动起来了，恢复 %d 秒票 @ %s",
						cartId, TICKET_TIMEOUT_TICKS / 20, pos);
			}
		}
		level.getChunkSource().addTicketWithRadius(
				idle ? MINECART_TICKET_IDLE : MINECART_TICKET, pos, ticketRadius);
	}

	/**
	 * 记录 / 更新一辆成功续票的矿车。位置变化（矿车在动）时才标脏，避免无谓写盘。
	 */
	private static void recordAnchor(ServerLevel level, Entity cart, long nowMs) {
		UUID id = cart.getUUID();
		BOOTSTRAPPING.remove(id);
		LAST_MATCHED.put(id, nowMs);
		ChunkPos pos = cart.chunkPosition();
		String dimensionId = DimensionIds.of(level.dimension());
		Anchor current = ANCHORS.get(id);
		if (current == null || !current.pos().equals(pos) || !current.dimensionId().equals(dimensionId)) {
			ANCHORS.put(id, new Anchor(dimensionId, pos));
			anchorsDirty = true;
		}
	}

	private static ServerLevel findLevel(MinecraftServer server, String dimensionId) {
		for (ServerLevel level : server.getAllLevels()) {
			if (DimensionIds.of(level.dimension()).equals(dimensionId)) {
				return level;
			}
		}
		return null;
	}

	/**
	 * 判断实体是否停在指定种类的铁轨上。矿车实体坐标通常落在铁轨方块内，
	 * 但不同版本/贴地情况可能差一格，所以自身位置与下方各查一次。
	 */
	private static boolean isOnRail(ServerLevel level, Entity entity, Set<String> rails) {
		BlockPos pos = entity.blockPosition();
		return matchesRail(level.getBlockState(pos), rails)
				|| matchesRail(level.getBlockState(pos.below()), rails);
	}

	/**
	 * 矿车是否正处在被活塞推动的方块里。
	 *
	 * <p>活塞动画期间，被推的方块（连同上面的铁轨）会临时变成 {@code moving_piston}，
	 * 这时 {@link #isOnRail} 必然判不到。对于已经被认定过的加载器矿车（有锚点），
	 * 这属于「机器在走」，不是「离开铁轨」，应当继续续票。</p>
	 */
	private static boolean isOnMovingPiston(ServerLevel level, Entity entity) {
		BlockPos pos = entity.blockPosition();
		return level.getBlockState(pos).is(Blocks.MOVING_PISTON)
				|| level.getBlockState(pos.below()).is(Blocks.MOVING_PISTON);
	}

	private static boolean matchesRail(BlockState state, Set<String> rails) {
		if (state.isAir()) {
			return false;
		}
		Block block = state.getBlock();
		return rails.contains(normalize(BuiltInRegistries.BLOCK.getKey(block).toString()));
	}

	private static String idOfEntity(Entity entity) {
		return normalize(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
	}

	/**
	 * 把 {@code namespace:path} 或 {@code path} 统一成小写 path，便于配置里省略 {@code minecraft:}。
	 */
	private static String normalize(String id) {
		String path = id.toLowerCase(Locale.ROOT);
		return path.startsWith("minecraft:") ? path.substring("minecraft:".length()) : path;
	}
}
