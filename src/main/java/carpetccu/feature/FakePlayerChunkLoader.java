package carpetccu.feature;

import carpet.patches.EntityPlayerMPFake;
import carpetccu.CarpetCcuServer;
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
 * <p>背景：原版里玩家（包括假人）周围加载多少区块是由<strong>服务器视距</strong>统一决定的，
 * 没有按玩家单独设置的入口。所以这里换一种做法：给指定假人额外挂一个
 * {@link TicketType} 的区块 ticket，把它周围 {@code 半径} 个区块强制保持在「实体刻」状态
 * （{@code ChunkLevel.byStatus(FULL)=33}，ticket 等级换算见下），未设定的假人则不加，
 * 维持原版视距的行为。</p>
 *
 * <p>ticket 等级换算：{@code TicketStorage#addTicketWithRadius(type, pos, r)} 会创建
 * {@code level = 33 - r} 的 ticket，而 ticket 等级每远一个区块 +1；实体刻要求等级 ≤ 31，
 * 因此想让半径 {@code R} 内的区块都实体刻，需要传 {@code r = R + 2}。</p>
 *
 * <p>注意：</p>
 * <ul>
 *     <li>本功能只能<strong>扩大</strong>加载范围。原版对玩家本身就有视距范围内的 ticket，
 *     所以设置值小于等于服务器视距时，效果等同未设置（无法缩小）。</li>
 *     <li>ticket 类型不带 {@code FLAG_PERSIST}，重启后不会残留在存档里。</li>
 *     <li>设置按假人名字保存，假人重新召唤后依然生效。</li>
 * </ul>
 */
public final class FakePlayerChunkLoader {

	/**
	 * 自定义 ticket 类型：加载 + 模拟，不持久化。
	 */
	private static final TicketType CCU_FAKE_PLAYER_TICKET = new TicketType(
			TicketType.NO_TIMEOUT,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	/**
	 * 半径上限，防止误操作把服务器拖死。
	 */
	public static final int MAX_RADIUS = 32;

	/**
	 * 每 20 刻检查一次位置变化，避免每刻都动 ticket。
	 */
	private static final int CHECK_INTERVAL = 20;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 假人名字（小写） -> 额外加载半径（区块）。
	 */
	private static final Map<String, Integer> RANGES = new HashMap<>();

	/**
	 * 已挂 ticket 的假人：UUID -> 当前生效的位置与半径。
	 */
	private static final Map<UUID, Applied> APPLIED = new HashMap<>();

	private FakePlayerChunkLoader() {
	}

	/**
	 * 某个假人当前生效的 ticket 记录。
	 */
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
			String content = Files.readString(path, StandardCharsets.UTF_8);
			JsonObject json = JsonParser.parseString(content).getAsJsonObject();
			for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
				int radius = entry.getValue().getAsInt();
				if (radius > 0) {
					RANGES.put(entry.getKey().toLowerCase(Locale.ROOT), Math.min(radius, MAX_RADIUS));
				}
			}
		} catch (Exception e) {
			CarpetCcuServer.LOGGER.warn("读取假人加载范围配置失败：{}", path, e);
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
			CarpetCcuServer.LOGGER.warn("保存假人加载范围配置失败：{}", path, e);
		}
	}

	/**
	 * 设置某个假人的额外加载半径。
	 *
	 * @param radius 0 或负数表示恢复默认（不再额外加载）
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
	 */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_INTERVAL != 0) {
			return;
		}

		// 1. 给在线假人维持 ticket
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
			if (current != null && current.radius() == wanted
					&& current.pos().equals(pos) && current.dimension().equals(dimension)) {
				continue;
			}

			if (current != null) {
				removeTicket(server, current);
			}
			addTicket(player, pos, wanted);
			APPLIED.put(id, new Applied(dimension, pos, wanted));
		}

		// 2. 清理已经下线/被移除的假人
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
