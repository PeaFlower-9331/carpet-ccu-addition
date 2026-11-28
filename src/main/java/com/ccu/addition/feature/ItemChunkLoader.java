package com.ccu.addition.feature;

import com.ccu.addition.CcuRules;
import com.ccu.addition.CcuServer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;

/**
 * 掉落物区块加载器。
 *
 * <p>只认**带自定义名字**的掉落物：每 5 秒给它的周围 3×3 区块挂强加载票，
 * 掉落物 5 分钟（6000 刻）后自然消失，票不再续期，最多 15 秒后过期。</p>
 *
 * <p><b>为什么是半径 2 的票 + 9 个区块各挂一张</b>：ticket 等级 = {@code 33 - 半径}，
 * 只有等级 ≤ 31 区块才跑实体刻。半径 1 的票只能让中心区块到 32 级（方块刻），
 * 掉落物根本不参与 tick，也就永远不会老化消失。所以这里对 3×3 里每个区块各挂一张半径 2 的票，
 * 让 3×3 全部处于实体刻（周围还会多一圈只加载不刻的区块，这是 ticket 传播的固有行为）。</p>
 *
 * <p>不做持久化：掉落物本身 5 分钟就没了，服务器重启后区块卸载、物品也扫不到，
 * 需要重新丢一个。</p>
 */
public final class ItemChunkLoader {

	/**
	 * 扫描间隔（刻），默认 100 刻 = 5 秒。
	 */
	private static final int INTERVAL_TICKS = 100;

	/**
	 * 票的有效期（刻）：15 秒，比扫描间隔长，漏一两轮也不会断加载。
	 */
	private static final long TICKET_TIMEOUT_TICKS = 300L;

	/**
	 * 票半径：{@code 33 - 2 = 31} 级 → 实体刻。
	 */
	private static final int TICKET_RADIUS = 2;

	/**
	 * 3×3 的展开半径。
	 */
	private static final int SPREAD = 1;

	/**
	 * 同时生效的加载器上限，防止有人拿一堆重命名物品铺加载区。
	 */
	private static final int MAX_LOADERS = 256;

	private static final TicketType ITEM_LOADER_TICKET = new TicketType(
			TICKET_TIMEOUT_TICKS,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	private static int lastLoaders = -1;

	private ItemChunkLoader() {
	}

	public static void tick(MinecraftServer server) {
		if (!CcuRules.namedItemChunkLoader) {
			lastLoaders = -1;
			return;
		}
		if (server.getTickCount() % INTERVAL_TICKS != 0) {
			return;
		}

		int loaders = 0;
		boolean capped = false;
		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (!(entity instanceof ItemEntity item) || !isLoader(item)) {
					continue;
				}
				if (loaders >= MAX_LOADERS) {
					capped = true;
					break;
				}
				loaders++;
				ChunkPos center = item.chunkPosition();
				for (int dx = -SPREAD; dx <= SPREAD; dx++) {
					for (int dz = -SPREAD; dz <= SPREAD; dz++) {
						level.getChunkSource().addTicketWithRadius(
								ITEM_LOADER_TICKET, new ChunkPos(center.x + dx, center.z + dz), TICKET_RADIUS);
					}
				}
			}
			if (capped) {
				break;
			}
		}

		if (loaders != lastLoaders) {
			lastLoaders = loaders;
			if (capped) {
				CcuServer.debug("掉落物加载器：%d 个（已达上限 %d，多余的忽略）", loaders, MAX_LOADERS);
			} else {
				CcuServer.debug("掉落物加载器：%d 个，每个维持周围 3×3 区块实体刻", loaders);
			}
		}
	}

	/**
	 * @return 这个掉落物是否算加载器（必须被重命名过）
	 */
	private static boolean isLoader(ItemEntity item) {
		return item.getItem().has(DataComponents.CUSTOM_NAME);
	}
}
