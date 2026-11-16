package com.ccu.addition.feature;

import com.ccu.addition.CcuServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.server.players.ProfileResolver;
import net.minecraft.world.item.component.ResolvableProfile;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假人档案预加载（召唤时触发）。
 *
 * <p>Carpet 的 {@code /player <名字> spawn} 在真正创建假人之前要先解析玩家档案（UUID + 皮肤），
 * 名字没缓存时会走一次网络查询，这段时间就表现为「召唤时卡一下」。本类把这一步拆出来：</p>
 *
 * <ol>
 *     <li>接到召唤请求后立刻返回，<strong>先在一个后台线程</strong>把该玩家的档案解析好；</li>
 *     <li>解析完成（无论成功失败）后回到服务器线程，再执行真正的召唤；</li>
 *     <li>此时 Carpet 再查档案就是缓存命中，召唤本身几乎无耗时。</li>
 * </ol>
 *
 * <p>同一个名字只预加载一次（成功或失败都算），后续召唤直接走原版流程。</p>
 */
public final class FakePlayerSpawnPreloader {

	/**
	 * 已经预加载过的名字（小写），后续召唤不再拦截。
	 */
	private static final Set<String> PRELOADED = ConcurrentHashMap.newKeySet();

	/**
	 * 正在预加载的名字，避免同一名连续召唤时重复拆包。
	 */
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();

	private FakePlayerSpawnPreloader() {
	}

	/**
	 * @return true 表示这次召唤需要先预加载（调用方应取消本次召唤并稍后重放）
	 */
	public static boolean shouldPreload(String name) {
		String key = name.toLowerCase(Locale.ROOT);
		if (PRELOADED.contains(key)) {
			return false;
		}
		return IN_FLIGHT.add(key);
	}

	/**
	 * 后台解析档案，完成后回到服务器线程执行 {@code spawnTask}。
	 */
	public static void preloadThen(MinecraftServer server, String name, Runnable spawnTask) {
		String key = name.toLowerCase(Locale.ROOT);

		CompletableFuture.runAsync(() -> resolve(server, name)).whenComplete((ignored, error) -> {
			IN_FLIGHT.remove(key);
			PRELOADED.add(key);
			try {
				server.execute(spawnTask);
			} catch (Throwable e) {
				CcuServer.LOGGER.warn("假人档案预加载后无法回到服务器线程执行召唤：{}", name, e);
			}
		});
	}

	private static void resolve(MinecraftServer server, String name) {
		try {
			ProfileResolver resolver = server.services().profileResolver();

			// Carpet 是按 UUID 解析档案的，所以这里也按 UUID 预加载，确保命中同一份缓存
			UUID uuid = OldUsersConverter.convertMobOwnerIfNecessary(server, name);
			if (uuid != null) {
				ResolvableProfile.createUnresolved(uuid).resolveProfile(resolver).join();
			}

			// 顺带按名字预热，供需要名字查询的路径使用
			ResolvableProfile.createUnresolved(name).resolveProfile(resolver).join();
		} catch (Throwable e) {
			// 解析失败也继续召唤，Carpet 自己会退回离线档案
			CcuServer.debug("假人档案预加载失败（不影响召唤）：%s %s", name, e);
		}
	}
}
