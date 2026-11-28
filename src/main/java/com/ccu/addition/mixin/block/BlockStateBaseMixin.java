package com.ccu.addition.mixin.block;

import com.ccu.addition.feature.NightmarishBlockStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 透明试炼方块：不导电、不可破坏、活塞可推穿、非玩家实体可穿过。
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

	@Inject(method = "isRedstoneConductor", at = @At("HEAD"), cancellable = true)
	private void ccu$noRedstone(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (matchesSelf()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "getDestroySpeed", at = @At("HEAD"), cancellable = true)
	private void ccu$unbreakable(BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (matchesSelf()) {
			cir.setReturnValue(-1.0F);
		}
	}

	@Inject(method = "getPistonPushReaction", at = @At("HEAD"), cancellable = true)
	private void ccu$pushReaction(CallbackInfoReturnable<PushReaction> cir) {
		if (matchesSelf()) {
			cir.setReturnValue(PushReaction.DESTROY);
		}
	}

	@Inject(
			method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
			at = @At("HEAD"),
			cancellable = true
	)
	private void ccu$ghostCollision(BlockGetter level, BlockPos pos, CollisionContext context,
	                                CallbackInfoReturnable<VoxelShape> cir) {
		if (!matchesSelf()) {
			return;
		}
		// 玩家（包括假人）保持原碰撞；其它实体（TNT、矿车、掉落物……）直接穿过
		if (context instanceof EntityCollisionContext entityContext
				&& !(entityContext.getEntity() instanceof Player)) {
			cir.setReturnValue(Shapes.empty());
		}
	}

	private boolean matchesSelf() {
		return NightmarishBlockStore.matchesBlock(
				((BlockBehaviour.BlockStateBase) (Object) this).getBlock());
	}
}
