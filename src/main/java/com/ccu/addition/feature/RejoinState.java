package com.ccu.addition.feature;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;

/**
 * 「本次召唤是 rejoin」的判定。
 *
 * <p>{@code rejoin} 命令由 <strong>Carpet TIS Addition</strong> 提供（{@code /player <名字> rejoin}），
 * 本模组不重复注册命令，只在自己的规则里读取它的标记：TIS 用
 * {@code FakePlayerRejoinHelper.isRejoin}（ThreadLocal）来标记「这次是 rejoin」。</p>
 *
 * <p>读取走反射 + {@link FabricLoader#isModLoaded} 判断，所以没装 TIS 时完全不碰它的类，
 * 也不会因为缺少依赖而报错——此时 {@link #isRejoin()} 恒为 false，本模组的规则自然不生效。</p>
 *
 * <p>另一个关键点：TIS 的标记只活在命令执行的那个线程/调用栈里，而 Carpet 的 spawn 主体跑在
 * {@code whenCompleteAsync} 回调里、我们的档案预加载还会把 {@code createFake} 再推迟一次。
 * 所以 {@link #runWith(boolean, Runnable)} 除了设置本模组的标记，也会同步设置 <em>TIS 的</em>
 * ThreadLocal——否则重放时 TIS 自己的 mixin 会以为「这次不是 rejoin」。</p>
 */
public final class RejoinState {

	/**
	 * TIS 的 mod id 在不同版本里出现过两种写法，两个都认。
	 */
	private static final String[] TIS_MOD_IDS = {"carpet-tis-addition", "carpettisaddition"};
	private static final String TIS_HELPER_CLASS =
			"carpettisaddition.helpers.carpet.tweaks.command.fakePlayerRejoin.FakePlayerRejoinHelper";

	/**
	 * 本模组内部的标记，用于把 rejoin 语义带过 Carpet 的异步 spawn 边界。
	 */
	private static final ThreadLocal<Boolean> LOCAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private static boolean tisResolved = false;
	private static ThreadLocal<Boolean> tisFlag = null;

	private RejoinState() {
	}

	public static boolean isRejoin() {
		if (LOCAL.get()) {
			return true;
		}
		return tisFlag();
	}

	/**
	 * 带着指定标记执行一段逻辑，结束后恢复原值（本模组标记与 TIS 标记一起处理）。
	 */
	public static void runWith(boolean value, Runnable action) {
		boolean previousLocal = LOCAL.get();
		boolean previousTis = tisFlag();
		LOCAL.set(value);
		setTis(value);
		try {
			action.run();
		} finally {
			if (previousLocal) {
				LOCAL.set(Boolean.TRUE);
			} else {
				LOCAL.remove();
			}
			setTis(previousTis);
		}
	}

	private static boolean tisFlag() {
		if (!tisLoaded()) {
			return false;
		}
		ThreadLocal<Boolean> flag = resolveTisFlag();
		return flag != null && Boolean.TRUE.equals(flag.get());
	}

	private static void setTis(boolean value) {
		if (!tisLoaded()) {
			return;
		}
		ThreadLocal<Boolean> flag = resolveTisFlag();
		if (flag == null) {
			return;
		}
		com.ccu.addition.CcuServer.debug("把 TIS 的 rejoin 标记设为 %s", value);
		if (value) {
			flag.set(Boolean.TRUE);
		} else {
			flag.remove();
		}
	}

	private static boolean tisLoaded() {
		for (String id : TIS_MOD_IDS) {
			if (FabricLoader.getInstance().isModLoaded(id)) {
				return true;
			}
		}
		return false;
	}

	private static ThreadLocal<Boolean> resolveTisFlag() {
		if (!tisResolved) {
			tisResolved = true;
			try {
				Class<?> helper = Class.forName(TIS_HELPER_CLASS);
				Field field = helper.getField("isRejoin");
				Object value = field.get(null);
				if (value instanceof ThreadLocal<?> threadLocal) {
					@SuppressWarnings("unchecked")
					ThreadLocal<Boolean> casted = (ThreadLocal<Boolean>) threadLocal;
					tisFlag = casted;
				}
			} catch (Throwable e) {
				// TIS 版本差异或字段改名：降级成「不是 rejoin」，并打日志便于排查
				com.ccu.addition.CcuServer.LOGGER.warn("读取 TIS 的 rejoin 标记失败：{}", e.toString());
				tisFlag = null;
			}
		}
		return tisFlag;
	}
}

