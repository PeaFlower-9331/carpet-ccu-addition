package com.ccu.addition.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.ccu.addition.CcuRules;
import com.ccu.addition.feature.RejoinState;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * {@code /player <名字> rejoin} 与规则 {@code fakePlayerKeepVehicle} 的召唤流程修正。
 *
 * <p>写法参考 Carpet TIS Addition 的 {@code fakePlayerRejoin}：{@code rejoin} 会置位
 * {@link RejoinState} 标记并复用 Carpet 的 {@code spawn}，本 mixin 在召唤 lambda 里按标记跳过：</p>
 *
 * <ol>
 *     <li>{@code teleportTo(...)} —— 否则会传送到命令执行点，而不是存档位置；</li>
 *     <li>{@code snapTo(...)} —— 起始位置吸附会再次把人拽走；</li>
 *     <li>{@code stopRiding()} —— 仅在规则 {@code fakePlayerKeepVehicle} 开启且确实恢复了载具时跳过，
 *     让假人保持原来的骑乘状态。</li>
 * </ol>
 *
 * <p>普通 {@code /player <名字> spawn} 不置位标记，三个调用照常执行，行为与 Carpet 原版一致。
 * 由于本 mixin 从不移动载具或假人的坐标，不会产生载具被挤压/贴地导致的偏移。</p>
 */
@Mixin(EntityPlayerMPFake.class)
public abstract class FakePlayerVehicleMixin {

	@WrapOperation(
			method = "lambda$createFake$2",
			at = @At(value = "INVOKE", target = "Lcarpet/patches/EntityPlayerMPFake;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z")
	)
	private static boolean ccu$skipTeleportOnRejoin(EntityPlayerMPFake instance, ServerLevel level, double x, double y,
	                                                 double z, Set<Relative> relatives, float yaw, float pitch,
	                                                 boolean resetCamera, Operation<Boolean> original) {
		if (RejoinState.isRejoin()) {
			return false;
		}
		return original.call(instance, level, x, y, z, relatives, yaw, pitch, resetCamera);
	}

	@WrapOperation(
			method = "lambda$createFake$1",
			at = @At(value = "INVOKE", target = "Lcarpet/patches/EntityPlayerMPFake;snapTo(DDDFF)V")
	)
	private static void ccu$skipSnapToOnRejoin(EntityPlayerMPFake instance, double x, double y, double z, float yaw,
	                                           float pitch, Operation<Void> original) {
		if (RejoinState.isRejoin()) {
			return;
		}
		original.call(instance, x, y, z, yaw, pitch);
	}

	@WrapWithCondition(
			method = "lambda$createFake$2",
			at = @At(value = "INVOKE", target = "Lcarpet/patches/EntityPlayerMPFake;stopRiding()V")
	)
	private static boolean ccu$keepVehicleOnRejoin(EntityPlayerMPFake instance) {
		boolean keepRiding = RejoinState.isRejoin()
				&& CcuRules.fakePlayerKeepVehicle
				&& instance.getVehicle() != null;
		return !keepRiding;
	}
}
