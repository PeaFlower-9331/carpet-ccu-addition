package com.ccu.addition.feature;

import com.ccu.addition.CcuServer;
import com.ccu.addition.CcuRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 矿车加载器。
 *
 * <p>当「指定种类的矿车」停在「指定种类的铁轨」上时，每 5 秒给它所在区块挂一次加载票，
 * 让周围 {@code minecartLoaderRange} 个区块保持加载与实体刻。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *     <li>ticket 类型自带 {@value #TICKET_TIMEOUT_TICKS} 刻超时，且不持久化；每 5 秒重复挂票会
 *     刷新超时（{@code TicketStorage.addTicket} 对同类型同等级的 ticket 调用 {@code resetTicksLeft}），
 *     矿车离开铁轨或消失后，票会在 5 秒内自然过期，不会留下残留。</li>
 *     <li>矿车移动后旧区块的票不立刻撤销，最多残留 5 秒。</li>
 *     <li>只扫描已加载的实体，不会为了找矿车而强行加载区块。</li>
 * </ul>
 */
public final class MinecartChunkLoader {

	/**
	 * 每次挂票的间隔与票的有效期：5 秒 = 100 刻。
	 */
	private static final int TICKET_TIMEOUT_TICKS = 100;

	private static final TicketType MINECART_TICKET = new TicketType(
			TICKET_TIMEOUT_TICKS,
			TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
	);

	private static final int MIN_RANGE = 1;
	private static final int MAX_RANGE = 32;

	private MinecartChunkLoader() {
	}

	public static void tick(MinecraftServer server) {
		// 每 5 秒（100 刻）给一次票
		if (server.getTickCount() % TICKET_TIMEOUT_TICKS != 0) {
			return;
		}
		if (!CcuRules.minecartChunkLoader) {
			return;
		}

		Set<String> types = parseIds(CcuRules.minecartLoaderTypes);
		Set<String> rails = parseIds(CcuRules.minecartLoaderRails);
		if (types.isEmpty() || rails.isEmpty()) {
			return;
		}

		int range = Math.max(MIN_RANGE, Math.min(MAX_RANGE, CcuRules.minecartLoaderRange));
		int loaded = 0;

		for (ServerLevel level : server.getAllLevels()) {
			for (Entity entity : level.getAllEntities()) {
				if (entity.isRemoved()) {
					continue;
				}
				// 只按注册表 id 判断种类，不依赖 AbstractMinecart 类（1.21.10 与 1.21.11 的包名不同）
				if (!types.contains(idOfEntity(entity))) {
					continue;
				}
				if (!isOnRail(level, entity, rails)) {
					continue;
				}
				// +2：把「半径 R 内实体刻」换算成 addTicketWithRadius 需要的 ticket 半径
				level.getChunkSource().addTicketWithRadius(MINECART_TICKET, entity.chunkPosition(), range + 2);
				loaded++;
			}
		}

		if (loaded > 0) {
			CcuServer.debug("矿车加载器：本轮为 %d 辆矿车续票（半径 %d 区块）", loaded, range);
		}
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

	private static Set<String> parseIds(String raw) {
		Set<String> ids = new HashSet<>();
		if (raw == null || raw.isBlank()) {
			return ids;
		}
		for (String part : raw.split("[,;\\s]+")) {
			String id = part.strip().toLowerCase(Locale.ROOT);
			if (id.startsWith("minecraft:")) {
				id = id.substring("minecraft:".length());
			}
			if (!id.isEmpty()) {
				ids.add(id);
			}
		}
		return ids;
	}
}
