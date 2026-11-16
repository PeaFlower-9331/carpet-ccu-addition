package com.ccu.addition.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.ccu.addition.CcuRules;
import com.ccu.addition.feature.FakePlayerSpawnPreloader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 规则 {@code fakePlayerSpawnPreload}：召唤假人时先异步预加载档案，再真正召唤。
 *
 * <p>拦截 {@code EntityPlayerMPFake#createFake} 的开头：如果这个名字还没预加载过，
 * 就先取消这次调用，把档案解析放到后台线程；解析完成后在服务器线程上重新调用一次
 * {@code createFake}（此时档案已在缓存里，Carpet 不会再等待网络）。</p>
 *
 * <p>{@code CCU$REENTER} 用来避免重放时再次拦截自己。</p>
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

	@Inject(method = "createFake", at = @At("HEAD"), cancellable = true)
	private static void ccu$preloadProfileBeforeSpawn(String username, MinecraftServer server, Vec3 pos,
	                                                  double yaw, double pitch, ResourceKey<Level> dimensionId,
	                                                  GameType gamemode, boolean flying,
	                                                  CallbackInfoReturnable<Boolean> cir) {
		if (!CcuRules.fakePlayerSpawnPreload || CCU_REENTER.get()) {
			return;
		}
		if (!FakePlayerSpawnPreloader.shouldPreload(username)) {
			return;
		}

		// 取消本次召唤，等档案就绪后重放
		cir.setReturnValue(true);
		FakePlayerSpawnPreloader.preloadThen(server, username, () -> {
			CCU_REENTER.set(Boolean.TRUE);
			try {
				createFake(username, server, pos, yaw, pitch, dimensionId, gamemode, flying);
			} finally {
				CCU_REENTER.set(Boolean.FALSE);
			}
		});
	}
}
