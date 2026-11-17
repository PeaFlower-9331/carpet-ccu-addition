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
 */
public final class RejoinState {

	private static final String TIS_MOD_ID = "carpettisaddition";
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
		return readTisFlag();
	}

	public static void begin() {
		LOCAL.set(Boolean.TRUE);
	}

	public static void end() {
		LOCAL.remove();
	}

	/**
	 * 带着指定标记执行一段逻辑，结束后恢复原值。
	 *
	 * <p>召唤预加载会把 {@code createFake} 推迟到档案解析完成之后执行，Carpet 的 spawn 主体也跑在
	 * {@code whenCompleteAsync} 回调里，都跨了任务边界，所以需要显式把标记传过去。</p>
	 */
	public static void runWith(boolean value, Runnable action) {
		boolean previous = LOCAL.get();
		LOCAL.set(value);
		try {
			action.run();
		} finally {
			if (previous) {
				LOCAL.set(Boolean.TRUE);
			} else {
				LOCAL.remove();
			}
		}
	}

	private static boolean readTisFlag() {
		if (!FabricLoader.getInstance().isModLoaded(TIS_MOD_ID)) {
			return false;
		}
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
			} catch (Throwable ignored) {
				// TIS 版本差异或字段改名：静默降级成「不是 rejoin」
				tisFlag = null;
			}
		}
		return tisFlag != null && Boolean.TRUE.equals(tisFlag.get());
	}
}
