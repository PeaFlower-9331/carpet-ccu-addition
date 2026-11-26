package com.ccu.addition.feature;

import com.ccu.addition.CcuServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 假人档案预加载的屏幕状态提示。
 *
 * <p>位置与 Carpet Org Addition 批量召唤时的进度提示一致：快捷栏上方的动作条
 * （{@code /player} 提示的那一行），不刷聊天框。</p>
 *
 * <p>两个刻意的设计：</p>
 * <ul>
 *     <li><b>慢才显示</b>：档案命中缓存时预加载只有几毫秒，闪一下反而碍眼；
 *     到这里满 {@value #MIN_VISIBLE_MS} 毫秒还没好才开始显示。</li>
 *     <li><b>按人聚合</b>：一次召唤多个假人（比如通过其它模组的批量召唤）时，
 *     同一个人名下会累计成「已完成 x / 共 y」，而不是一人一条。</li>
 * </ul>
 */
public final class PreloadStatus {

	/**
	 * 低于这个耗时完全不打扰玩家（毫秒）。
	 */
	private static final long MIN_VISIBLE_MS = 300L;

	/**
	 * 显示中的刷新间隔（毫秒）。动作条本身几秒后会消失，慢慢刷一次就够了。
	 */
	private static final long REFRESH_MS = 1_000L;

	/**
	 * 当前线程上「正在执行 /player <名字> spawn」的玩家，由命令 mixin 写入。
	 */
	private static final ThreadLocal<ServerPlayer> SOURCE = new ThreadLocal<>();

	/**
	 * 玩家 UUID -> 该玩家名下正在进行的预加载。
	 */
	private static final Map<UUID, Pending> ACTIVE = new HashMap<>();

	private PreloadStatus() {
	}

	/**
	 * 一次预加载的累计状态。
	 */
	private static final class Pending {
		private final ServerPlayer player;
		private final long startedMs;
		private int total;
		private int done;
		private String lastName = "";
		private long lastShownMs;

		private Pending(ServerPlayer player, long now) {
			this.player = player;
			this.startedMs = now;
		}
	}

	// ---------------------------------------------------------------- 命令线程的上下文

	public static void enterSource(ServerPlayer player) {
		if (player == null) {
			SOURCE.remove();
		} else {
			SOURCE.set(player);
		}
	}

	/**
	 * 取走当前线程上的来源玩家并清空，避免异常路径留下的值被别的召唤误用。
	 */
	public static ServerPlayer takeSource() {
		ServerPlayer player = SOURCE.get();
		SOURCE.remove();
		return player;
	}

	public static void clearSource() {
		SOURCE.remove();
	}

	// ---------------------------------------------------------------- 预加载状态

	/**
	 * 一次预加载开始。
	 */
	public static void begin(ServerPlayer source, String name) {
		if (source == null) {
			return;
		}
		long now = System.currentTimeMillis();
		Pending pending = ACTIVE.get(source.getUUID());
		if (pending == null) {
			pending = new Pending(source, now);
			ACTIVE.put(source.getUUID(), pending);
		}
		pending.total++;
		pending.lastName = name;
		CcuServer.debug("预加载：%s 由 %s 发起，等待档案", name, source.getScoreboardName());
	}

	/**
	 * 一次预加载结束（解析成功或失败都算）。
	 */
	public static void finish(ServerPlayer source, String name) {
		if (source == null) {
			return;
		}
		Pending pending = ACTIVE.get(source.getUUID());
		if (pending == null) {
			return;
		}
		pending.done++;
		pending.lastName = name;
		CcuServer.debug("预加载：%s 已结束（%d/%d）", name, pending.done, pending.total);
		if (pending.done < pending.total) {
			return;
		}
		ACTIVE.remove(source.getUUID());
		long elapsed = System.currentTimeMillis() - pending.startedMs;
		if (elapsed < MIN_VISIBLE_MS) {
			// 太快了，从头到尾都没显示过，收尾也别打扰
			return;
		}
		show(source, pending.total == 1
				? pending.lastName + " 的档案已就绪，正在召唤"
				: "档案预加载完成（" + pending.total + " 个），开始召唤");
	}

	/**
	 * 每刻推进一次：该刷新的刷新，该清人的清人。
	 */
	public static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<UUID, Pending>> iterator = ACTIVE.entrySet().iterator();
		while (iterator.hasNext()) {
			Pending pending = iterator.next().getValue();
			// 玩家中途下线就别再显示，也别一直挂着
			if (server.getPlayerList().getPlayer(pending.player.getUUID()) == null) {
				iterator.remove();
				continue;
			}
			if (now - pending.startedMs < MIN_VISIBLE_MS) {
				continue;
			}
			if (now - pending.lastShownMs < REFRESH_MS) {
				continue;
			}
			pending.lastShownMs = now;
			show(pending.player, progressText(pending));
		}
	}

	private static String progressText(Pending pending) {
		if (pending.total <= 1) {
			return "正在预加载 " + pending.lastName + " 的档案…";
		}
		return "正在预加载假人档案… 已完成 " + pending.done + " / " + pending.total;
	}

	private static void show(ServerPlayer player, String text) {
		CcuServer.debug("预加载状态 -> %s：%s", player.getScoreboardName(), text);
		player.displayClientMessage(Component.literal(text), true);
	}
}
