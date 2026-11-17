package com.ccu.addition.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.ccu.addition.CcuRules;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 规则 {@code fakePlayerKeepVehicle} 的配套保险：优先复用世界里已经存在的载具。
 *
 * <p>原版 {@code ServerPlayer#loadAndSpawnParentVehicle} 会按 {@code RootVehicle} 的 NBT
 * <strong>新建一个实体对象</strong>再让玩家骑上去。如果那辆载具其实还活在世界上（区块已加载），
 * 就会出现「旧车还在 + 新车被骑」的两辆重复载具。</p>
 *
 * <p>这里拦下这次 {@code startRiding}：只要世界里已有同 UUID 的实体，就改骑那一辆旧车，
 * 新建的实体自然不会被加进世界，重复载具也就不会出现。查不到同 UUID 实体时保持原版行为。</p>
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerVehicleMixin {

	@WrapOperation(
			method = "loadAndSpawnParentVehicle",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerPlayer;startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z"
			)
	)
	private boolean ccu$reuseExistingVehicle(ServerPlayer self, Entity vehicle, boolean force, boolean emitEvent,
	                                          Operation<Boolean> original) {
		if (CcuRules.fakePlayerKeepVehicle && self instanceof EntityPlayerMPFake
				&& self.level() instanceof ServerLevel level) {
			Entity existing = level.getEntity(vehicle.getUUID());
			if (existing != null && existing != vehicle && !existing.isRemoved()) {
				return original.call(self, existing, force, emitEvent);
			}
		}
		return original.call(self, vehicle, force, emitEvent);
	}
}
