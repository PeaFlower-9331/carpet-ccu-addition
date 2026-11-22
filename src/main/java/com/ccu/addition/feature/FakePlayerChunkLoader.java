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
 */
public final class FakePlayerChunkLoader {

	private static final TicketType CCU_FAKE_PLAYER_TICKET = new TicketType(
			TicketType.NO_TIMEOUT,
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
	 * 周期性对账：按配置给假人挂/撤 ticket。
	 *
	 * <p>每个周期都无条件重挂一次票，而不是「位置没变就跳过」，原因见
	 * {@link #addTicket}：原版对同类型同等级的 ticket 只保留一条，多个假人共用，
	 * 别人撤票时会连带撤掉本假人的覆盖。</p>
	 */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_INTERVAL != 0) {
			return;
		}

		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player instanceof EntityPlayerMPFake)) {
				continue;
			}

			int wanted = getRange(player.getScoreboardName());
			UUID id = player.getUUID();
			Applied current = APPLIED.get(id);

			if (wanted <= 0) {
				if (current != null) {
					removeTicket(server, current);
					APPLIED.remove(id);
				}
				continue;
			}

			ResourceKey<Level> dimension = player.level().dimension();
			ChunkPos pos = player.chunkPosition();
			// 位置、半径或维度变了才撤旧票；撤完置空，下面会重新记录
			if (current != null && (current.radius() != wanted
					|| !current.pos().equals(pos)
					|| !current.dimension().equals(dimension))) {
				removeTicket(server, current);
				current = null;
			}

			// 无条件重挂：原版 TicketStorage 对「同类型 + 同等级」的 ticket
			// 只 resetTicksLeft 去重，不会累加引用，因此两个假人半径重叠时
			// 共享的是同一条 ticket —— 其中一个离开时会把另一个的票一起删掉。
			// 每 20 刻补挂一次，被误删的覆盖最多 1 秒后自动恢复。
			addTicket(player, pos, wanted);
			if (current == null) {
				APPLIED.put(id, new Applied(dimension, pos, wanted));
			}
		}

		// 清理已经下线/被移除的假人
		Iterator<Map.Entry<UUID, Applied>> iterator = APPLIED.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Applied> entry = iterator.next();
			if (server.getPlayerList().getPlayer(entry.getKey()) == null) {
				removeTicket(server, entry.getValue());
				iterator.remove();
			}
		}
	}

	/**
	 * 假人下线时立即撤掉 ticket（不必等下一次对账）。
	 */
	public static void forget(MinecraftServer server, UUID playerId) {
		Applied applied = APPLIED.remove(playerId);
		if (applied != null) {
			removeTicket(server, applied);
		}
	}

	private static void addTicket(ServerPlayer player, ChunkPos pos, int radius) {
		if (player.level() instanceof ServerLevel level) {
			// +2：把「半径 R 内实体刻」换算成 addTicketWithRadius 需要的 ticket 半径
			//
			// 注意：原版 TicketStorage#addTicket 对「同类型 + 同等级」的 ticket 只做
			// resetTicksLeft 去重，不会累加引用计数，所以这里对同一块区块重复挂票是
			// 幂等的；反过来，removeTicket 会把整条 ticket 删掉，不区分是谁挂的。
			level.getChunkSource().addTicketWithRadius(CCU_FAKE_PLAYER_TICKET, pos, radius + 2);
		}
	}

	private static void removeTicket(MinecraftServer server, Applied applied) {
		ServerLevel level = server.getLevel(applied.dimension());
		if (level != null) {
			level.getChunkSource().removeTicketWithRadius(CCU_FAKE_PLAYER_TICKET, applied.pos(), applied.radius() + 2);
		}
	}
}
