package com.ccu.addition.feature;

import carpet.patches.EntityPlayerMPFake;
import com.ccu.addition.CcuRules;
import com.ccu.addition.CcuServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 假人「重新上线后回到原来的载具」。
 *
 * <p>三条重新上线的路径有同一个问题：假人都会被从载具上摘下来。</p>
 *
 * <ul>
 *     <li><b>GCA 假人驻留</b>（GugleCarpetAddition 的 {@code fakePlayerResident}）：它不走
 *     Carpet 的 {@code createFake}，而是自己 {@code respawnFake} + {@code placeNewPlayer} +
 *     {@code teleportTo}，全程没有恢复载具；</li>
 *     <li><b>TIS 的 {@code /player <名字> rejoin}</b>：走 {@code createFake}，最后会被
 *     {@code stopRiding()} 踢下坐骑；</li>
 *     <li>直接 {@code /player <名字> spawn}：本来就该下车，本类不处理（见下）。</li>
 * </ul>
 *
 * <p>原版 1.21.9 起把「玩家退出时骑着的载具」写进 playerdata 的 {@code RootVehicle}，
 * 并在登录流程里由 {@code PrepareSpawnTask} 调 {@code loadAndSpawnParentVehicle} 恢复。
 * 假人绕过整个登录流程，所以这一步永远不会发生——{@code RootVehicle} 只是躺在存档里。</p>
 *
 * <p>做法：假人登录时读它自己的 playerdata，取出 {@code RootVehicle.Attach}（载具的 UUID），
 * 然后等载具所在区块被加载出来，再把它骑回去。载具本身是普通实体，一直存在存档里，
 * 所以这里<strong>只重新上座，不生成任何新实体</strong>，不会出现重复的矿车。</p>
 *
 * <p>两个保护：</p>
 * <ol>
 *     <li><b>距离</b>：载具必须离假人 8 格以内才上座。正常恢复时假人就站在载具原来的位置旁边；
 *     如果玩家手动 {@code /player ... spawn} 到别处，这个判断会直接放弃，不会把人拽回老地方。</li>
 *     <li><b>超时</b>：只等 15 秒（真实时间）。区块一直没加载出来就放弃，不会无限重试。</li>
 * </ol>
 */
public final class FakePlayerRideRestore {

	/**
	 * 等待载具区块加载的时间（真实时间，毫秒）。服务器跑 60～100 tps 时按刻计时会偏短，这里按毫秒算。
	 */
	private static final long WINDOW_MS = 15_000L;

	/**
	 * 允许的上座距离（格）。超过这个距离就认为「假人不是在那个载具旁边上线的」，放弃。
	 */
	private static final double MAX_DISTANCE_SQ = 64.0;

	/**
	 * 假人 UUID -> 等待上座的载具 UUID 与截止时间。
	 */
	private static final Map<UUID, Pending> PENDING = new HashMap<>();

	private FakePlayerRideRestore() {
	}

	private record Pending(UUID vehicle, long deadlineMs) {
	}

	// ---------------------------------------------------------------- 生命周期

	/**
	 * 假人登录时调用：从 playerdata 里读出它上次骑的载具，登记成待恢复。
	 */
	public static void onLogin(ServerPlayer player) {
		if (!CcuRules.fakePlayerRejoinKeepRiding) {
			return;
		}
		if (!(player instanceof EntityPlayerMPFake)) {
			return;
		}
		// 存档数据已经把载具恢复好了（装了别的同时做这件事的 mod）就不要插手
		if (player.getVehicle() != null) {
			return;
		}
		if (!(player.level() instanceof ServerLevel level)) {
			return;
		}
		UUID vehicle = savedVehicle(level.getServer(), player.getUUID());
		if (vehicle == null) {
			return;
		}

		PENDING.put(player.getUUID(), new Pending(vehicle, System.currentTimeMillis() + WINDOW_MS));
		CcuServer.debug("假人 %s 的存档里记着载具 %s，等它所在区块加载后恢复骑乘", player.getScoreboardName(), vehicle);
	}

	/**
	 * 每刻推进一次待恢复项：区块加载是异步的，载具实体要等区块出来才查得到。
	 */
	public static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}

		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<UUID, Pending> entry = iterator.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				// 假人又下线了，不再等
				iterator.remove();
				continue;
			}
			if (player.getVehicle() != null) {
				// 已经骑上了（别的路径恢复的），收工
				iterator.remove();
				continue;
			}
			if (now > entry.getValue().deadlineMs()) {
				iterator.remove();
				CcuServer.debug("假人 %s 等待载具 %s 超过 15 秒，放弃恢复骑乘",
						player.getScoreboardName(), entry.getValue().vehicle());
				continue;
			}
			if (!(player.level() instanceof ServerLevel level)) {
				iterator.remove();
				continue;
			}

			Entity vehicle = level.getEntity(entry.getValue().vehicle());
			if (vehicle == null || vehicle.isRemoved()) {
				// 区块还没加载出来，下一拍再看
				continue;
			}
			if (vehicle.distanceToSqr(player) > MAX_DISTANCE_SQ) {
				iterator.remove();
				CcuServer.debug("假人 %s 距离载具 %s 太远（超过 8 格），不恢复骑乘",
						player.getScoreboardName(), entry.getValue().vehicle());
				continue;
			}
			if (player.startRiding(vehicle)) {
				iterator.remove();
				CcuServer.debug("假人 %s 已恢复乘坐载具 %s @ %s",
						player.getScoreboardName(), vehicle.getType(), vehicle.blockPosition());
			}
		}
	}

	/**
	 * 假人下线时丢掉它的待恢复项。
	 */
	public static void forget(UUID playerId) {
		PENDING.remove(playerId);
	}

	// ---------------------------------------------------------------- 存档读取

	/**
	 * 读假人 playerdata 里记录的载具 UUID；没有骑乘记录时返回 {@code null}。
	 */
	private static UUID savedVehicle(MinecraftServer server, UUID playerId) {
		Path path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(playerId + ".dat");
		if (!Files.isRegularFile(path)) {
			return null;
		}
		try {
			CompoundTag data = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
			CompoundTag root = data.getCompound("RootVehicle").orElse(null);
			if (root == null) {
				return null;
			}
			// 首选 Attach：它是「谁坐在谁身上」的权威记录
			UUID vehicle = uuidFrom(root.getIntArray("Attach").orElse(null));
			if (vehicle != null) {
				return vehicle;
			}
			// 兜底：退化到实体自身的 UUID
			return root.getCompound("Entity")
					.flatMap(entity -> entity.getIntArray("UUID"))
					.map(FakePlayerRideRestore::uuidFrom)
					.orElse(null);
		} catch (Exception e) {
			CcuServer.LOGGER.warn("读取假人 {} 的载具记录失败：{}", playerId, path, e);
			return null;
		}
	}

	/**
	 * int[4] 形式的 UUID（NBT 里存的就是这个形状）转 {@link UUID}。
	 */
	private static UUID uuidFrom(int[] raw) {
		if (raw == null || raw.length != 4) {
			return null;
		}
		long most = ((long) raw[0] << 32) | (raw[1] & 0xFFFFFFFFL);
		long least = ((long) raw[2] << 32) | (raw[3] & 0xFFFFFFFFL);
		return new UUID(most, least);
	}
}
