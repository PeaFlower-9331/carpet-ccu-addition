package com.ccu.addition.feature;

import carpet.patches.EntityPlayerMPFake;
import com.ccu.addition.CcuServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 假人加载范围（额外区块 ticket）。
 *
 * <p>原版对玩家（含假人）的区块加载半径由<strong>服务器视距 / 模拟距离统一决定</strong>，
 * 没有按玩家单独配置的入口；而区块 ticket 只能叠加、不能抵消。因此本功能<strong>只能调大</strong>：
 * 给指定假人额外挂一个 ticket，把它周围 {@code 半径} 个区块强制保持加载并参与实体刻；
 * 未设置的假人不加任何 ticket，行为与原版完全一致。</p>
 *
 * <p>把半径设成小于等于服务器视距没有意义——原版那部分 ticket 依然存在，压不下去，
 * 所以命令在遇到这种取值时会给出提示。</p>
 *
 * <p>ticket 等级换算：{@code addTicketWithRadius(type, pos, r)} 创建 {@code level = 33 - r} 的 ticket，
 * 每远一个区块 +1，实体刻要求等级 ≤ 31，故「半径 R 内实体刻」传 {@code r = R + 2}。</p>
 *
 * <p><b>只挂票，不撤票</b>：区块 ticket 没有引用计数，两个假人半径重叠时共用的是同一条
 * ticket，任何一个去 {@code removeTicket} 都会把另一个的覆盖一起删掉。所以这里干脆不撤：
 * 每 {@value #CHECK_INTERVAL} 刻按假人当前所在区块强制补挂一次，换位置 / 下线就让旧票自己过期
 * （15 秒）。这样既没有「别人撤票导致自己掉加载」的问题，也不会留下长期残留。</p>
 */
public final class FakePlayerChunkLoader {

	/**
	 * 自带 15 秒超时：因为下面「只挂不撤」，票必须能自己过期，否则假人换位置后会一直吊着旧区块。
	 */
	private static final TicketType CCU_FAKE_PLAYER_TICKET = new TicketType(
			300L,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	/**
	 * 半径上限，防止误操作把服务器拖死。
	 */
	public static final int MAX_RADIUS = 32;

	/**
	 * 每 20 刻对账一次。
	 */
	private static final int CHECK_INTERVAL = 20;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 假人名字（小写） -> 加载半径（区块）。
	 */
	private static final Map<String, Integer> RANGES = new HashMap<>();

	/**
	 * 已挂 ticket 的假人：UUID -> 当前生效的位置与半径。
	 */
	private static final Map<UUID, Applied> APPLIED = new HashMap<>();

	private FakePlayerChunkLoader() {
	}

	private record Applied(ResourceKey<Level> dimension, ChunkPos pos, int radius) {
	}

	// ---------------------------------------------------------------- 配置

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve("carpet-ccu-addition").resolve("load-range.json");
	}

	/**
	 * 读取配置文件。文件不存在时视为空配置。
	 */
	public static void load() {
		RANGES.clear();
		Path path = configPath();
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
				int radius = entry.getValue().getAsInt();
				if (radius > 0) {
					RANGES.put(entry.getKey().toLowerCase(Locale.ROOT), Math.min(radius, MAX_RADIUS));
				}
			}
		} catch (Exception e) {
			CcuServer.LOGGER.warn("读取假人加载范围配置失败：{}", path, e);
		}
	}

	private static void save() {
		Path path = configPath();
		try {
			Files.createDirectories(path.getParent());
			JsonObject json = new JsonObject();
			RANGES.forEach(json::addProperty);
			Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
		} catch (Exception e) {
			CcuServer.LOGGER.warn("保存假人加载范围配置失败：{}", path, e);
		}
	}

	/**
	 * 设置某个假人的额外加载半径。
	 *
	 * @param radius 0 或负数表示清除设置（恢复成原版视距，不再额外加载）
	 * @return 之前的设置值（0 表示之前未设置）
	 */
	public static int setRange(String name, int radius) {
		String key = name.toLowerCase(Locale.ROOT);
		Integer previous = RANGES.remove(key);
		if (radius > 0) {
			RANGES.put(key, Math.min(radius, MAX_RADIUS));
		}
		save();
		return previous == null ? 0 : previous;
	}

	/**
	 * @return 该假人设置的半径，未设置返回 0
	 */
	public static int getRange(String name) {
		return RANGES.getOrDefault(name.toLowerCase(Locale.ROOT), 0);
	}

	/**
	 * @return 全部设置（名字 -> 半径），供命令列出
	 */
	public static Map<String, Integer> allRanges() {
		return Map.copyOf(RANGES);
	}

	// ---------------------------------------------------------------- 运行期

	/**
	 * 每 {@value #CHECK_INTERVAL} 刻对账一次：按配置给假人所在区块强制挂票，从不撤票。
	 */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_INTERVAL != 0) {
			return;
		}
		applyNow(server);
	}

	/**
	 * 立即对账一次，不受 {@value #CHECK_INTERVAL} 刻周期限制。
	 *
	 * <p>命令刚改完设置时调用，否则要等下一个 20 刻边界才生效。</p>
	 *
	 * <p>每个周期都无条件补挂一次而不是「位置没变就跳过」，原因见类注释：原版对同类型同等级的
	 * ticket 只保留一条，多个假人共用，别人撤票会连带撤掉本假人的覆盖。既然这里永远不撤，
	 * 共用也就没有副作用，补挂只是把票的超时一直续着。</p>
	 */
	public static void applyNow(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player instanceof EntityPlayerMPFake)) {
				continue;
			}

			String name = player.getScoreboardName();
			int wanted = getRange(name);
			UUID id = player.getUUID();

			if (wanted <= 0) {
				APPLIED.remove(id);
				continue;
			}

			ResourceKey<Level> dimension = player.level().dimension();
			ChunkPos pos = player.chunkPosition();
			Applied current = APPLIED.get(id);
			if (current == null || current.radius() != wanted
					|| !current.pos().equals(pos) || !current.dimension().equals(dimension)) {
				// 位置 / 半径变化只影响日志；旧区块的票交给超时自然回收
				APPLIED.put(id, new Applied(dimension, pos, wanted));
				CcuServer.debug("假人 %s：额外加载 %d 区块，已给 %s 补票", name, wanted, pos);
			}

			addTicket(player, pos, wanted);
		}

		// 已经下线的假人只需要清掉日志状态，票留在原地等超时
		APPLIED.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
	}

	/**
	 * 假人下线时清掉它的记录；票不撤，最多 15 秒后自然过期。
	 */
	public static void forget(UUID playerId) {
		APPLIED.remove(playerId);
	}

	private static void addTicket(ServerPlayer player, ChunkPos pos, int radius) {
		if (player.level() instanceof ServerLevel level) {
			// +2：把「半径 R 内实体刻」换算成 addTicketWithRadius 需要的 ticket 半径
			//
			// 原版 TicketStorage#addTicket 对「同类型 + 同等级」的 ticket 只做 resetTicksLeft
			// 去重，不会累加引用计数：所以重复挂票是幂等的，而撤票会删掉整条、不区分是谁挂的。
			// 这也是本类只挂不撤的原因。
			level.getChunkSource().addTicketWithRadius(CCU_FAKE_PLAYER_TICKET, pos, radius + 2);
		}
	}
}
