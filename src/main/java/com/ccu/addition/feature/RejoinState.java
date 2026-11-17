package com.ccu.addition.feature;

/**
 * 「本次召唤是 rejoin」的线程内标记。
 *
 * <p>参考 Carpet TIS Addition 的 {@code FakePlayerRejoinHelper.isRejoin}：{@code /player <名字> rejoin}
 * 会先置位这个标记，再复用 Carpet 的 {@code spawn} 逻辑；Carpet 召唤流程里的 mixin 读取它，
 * 从而跳过位置修正与下坐骑，让假人回到存档位置、保持原来的骑乘状态。</p>
 *
 * <p>普通 {@code /player <名字> spawn} 不会置位，行为与 Carpet 原版完全一致。</p>
 */
public final class RejoinState {

	private static final ThreadLocal<Boolean> REJOIN = ThreadLocal.withInitial(() -> Boolean.FALSE);

	private RejoinState() {
	}

	public static boolean isRejoin() {
		return REJOIN.get();
	}

	public static void begin() {
		REJOIN.set(Boolean.TRUE);
	}

	public static void end() {
		REJOIN.remove();
	}

	/**
	 * 带着指定标记执行一段逻辑，结束后恢复原值。
	 *
	 * <p>召唤预加载会把 {@code createFake} 推迟到档案解析完成之后执行，跨了任务/线程边界，
	 * 所以需要在这里显式把标记传过去。</p>
	 */
	public static void runWith(boolean value, Runnable action) {
		boolean previous = REJOIN.get();
		REJOIN.set(value);
		try {
			action.run();
		} finally {
			if (previous) {
				REJOIN.set(Boolean.TRUE);
			} else {
				REJOIN.remove();
			}
		}
	}
}
