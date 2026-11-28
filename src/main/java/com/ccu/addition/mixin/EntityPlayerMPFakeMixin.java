package com.ccu.addition.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.ccu.addition.CcuRules;
import com.ccu.addition.feature.FakePlayerSpawnPreloader;
import com.ccu.addition.feature.PreloadStatus;
import com.ccu.addition.feature.RejoinState;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * 规则 {@code fakePlayerSpawnPreload}：召唤假人时先异步预加载档案，再真正召唤。
 *
 * <p>拦截 {@code EntityPlayerMPFake#createFake} 的开头：如果这个名字还没预加载过，
 * 就先取消这次调用，把档案解析放到后台线程；解析完成后在服务器线程上重新调用一次
 * {@code createFake}（此时档案已在缓存里，Carpet 不会再等待网络）。</p>
 *
 * <p>{@code CCU_REENTER} 用来避免重放时再次拦截自己。</p>
 */
@Mixin(EntityPlayerMPFake.class)
public abstract class EntityPlayerMPFakeMixin {

	@Unique
	private static final ThreadLocal<Boolean> CCU_REENTER = ThreadLocal.withInitial(() -> Boolean.FALSE);

	@Shadow
	public static boolean createFake(String username, MinecraftServer server, Vec3 pos, double yaw, double pitch,
	                                 ResourceKey<Level> dimensionId, GameType gamemode, boolean flying) {
		throw new AssertionError("shadow");
	}

	/**
	 * Carpet 的 spawn 主体跑在 {@code whenCompleteAsync(..., server)} 的回调里，
	 * 那时 rejoin 命令早已执行完、线程标记被清掉；这里把标记包进回调再执行。
	 */
	@WrapOperation(
			method = "createFake",
			at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;whenCompleteAsync(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;")
	)
	private static <T> CompletableFuture<T> ccu$propagateRejoinFlag(CompletableFuture<T> future,
	                                                                  BiConsumer<? super T, ? super Throwable> action,
	                                                                  Executor executor,
	                                                                  Operation<CompletableFuture<T>> original) {
		boolean rejoin = RejoinState.isRejoin();
		BiConsumer<? super T, ? super Throwable> wrapped =
				(value, throwable) -> RejoinState.runWith(rejoin, () -> action.accept(value, throwable));
		return original.call(future, wrapped, executor);
	}

	@Inject(method = "createFake", at = @At("HEAD"), cancellable = true)
	private static void ccu$preloadProfileBeforeSpawn(String username, MinecraftServer server, Vec3 pos,
	                                                  double yaw, double pitch, ResourceKey<Level> dimensionId,
	                                                  GameType gamemode, boolean flying,
	                                                  CallbackInfoReturnable<Boolean> cir) {
		// rejoin 不推迟：TIS 的标记活在本线程调用栈里，推迟重放会丢掉它
		if (!CcuRules.fakePlayerSpawnPreload || CCU_REENTER.get() || RejoinState.isRejoin()) {
			return;
		}
		if (!FakePlayerSpawnPreloader.shouldPreload(username)) {
			return;
		}

		// 取消本次召唤，等档案就绪后重放
		cir.setReturnValue(true);
		// 谁执行的这条 /player spawn：拿来做屏幕上的预加载状态
		ServerPlayer source = PreloadStatus.takeSource();
		PreloadStatus.begin(source, username);
		FakePlayerSpawnPreloader.preloadThen(server, username, () -> {
			PreloadStatus.finish(source, username);
			CCU_REENTER.set(Boolean.TRUE);
			try {
				createFake(username, server, pos, yaw, pitch, dimensionId, gamemode, flying);
			} finally {
				CCU_REENTER.set(Boolean.FALSE);
			}
		});
	}
}
