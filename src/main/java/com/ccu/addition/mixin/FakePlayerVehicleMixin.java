package com.ccu.addition.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.ccu.addition.CcuRules;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

/**
 * 规则 {@code fakePlayerKeepVehicle}：假人重登时保留之前乘坐的载具。
 *
 * <p>做法参考 Carpet Org Addition 的「原始位置生成」：不去搬动载具，而是让 Carpet 的召唤流程
 * <strong>跳过三个会破坏骑乘状态的调用</strong>：</p>
 *
 * <ol>
 *     <li>{@code instance.stopRiding()} —— Carpet 读完存档后主动把假人踢下載具；</li>
 *     <li>{@code instance.teleportTo(...)} —— 把假人传送到召唤点，传送本身会让乘客下车；</li>
 *     <li>{@code instance.snapTo(...)} —— 召唤起始位置的吸附（{@code fixStartingPosition}）。</li>
 * </ol>
 *
 * <p>三个调用都在 Carpet 的 {@code lambda$createFake$N} 里，1.4.188 与 1.4.193 的编号一致
 * （{@code $2} 是主流程、{@code $1} 是起始位置吸附）。触发条件很窄：只有「这次召唤确实从存档里
 * 恢复了载具」（即 {@code getVehicle() != null}）才跳过，没载具的假人行为与 Carpet 原版完全一致。</p>
 *
 * <p>因为不搬动载具、也不做任何传送，所以不会出现载具被挤压/贴地导致的坐标偏移。</p>
 */
@Mixin(EntityPlayerMPFake.class)
public abstract class FakePlayerVehicleMixin {

	/**
	 * 这次召唤是否应当保留载具与原始位置。
	 */
	private static boolean ccu$keepRiding(EntityPlayerMPFake instance) {
		return CcuRules.fakePlayerKeepVehicle && instance.getVehicle() != null;
	}

	@WrapWithCondition(
			method = "lambda$createFake$2",
			at = @At(value = "INVOKE", target = "Lcarpet/patches/EntityPlayerMPFake;stopRiding()V")
	)
	private static boolean ccu$skipStopRiding(EntityPlayerMPFake instance) {
		return !ccu$keepRiding(instance);
	}

	@WrapOperation(
			method = "lambda$createFake$2",
			at = @At(value = "INVOKE", target = "Lcarpet/patches/EntityPlayerMPFake;teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z")
	)
	private static boolean ccu$skipTeleport(EntityPlayerMPFake instance, ServerLevel level, double x, double y, double z,
	                                         Set<Relative> relatives, float yaw, float pitch, boolean resetCamera,
	                                         Operation<Boolean> original) {
		if (ccu$keepRiding(instance)) {
			return false;
		}
		return original.call(instance, level, x, y, z, relatives, yaw, pitch, resetCamera);
	}

	@WrapOperation(
			method = "lambda$createFake$1",
			at = @At(value = "INVOKE", target = "Lcarpet/patches/EntityPlayerMPFake;snapTo(DDDFF)V")
	)
	private static void ccu$skipSnapTo(EntityPlayerMPFake instance, double x, double y, double z, float yaw, float pitch,
	                                   Operation<Void> original) {
		if (ccu$keepRiding(instance)) {
			return;
		}
		original.call(instance, x, y, z, yaw, pitch);
	}

	/**
	 * 供注释引用，避免 IDE 把 import 判成未使用。
	 */
	private static Entity ccu$unused(Entity entity) {
		return entity;
	}
}
