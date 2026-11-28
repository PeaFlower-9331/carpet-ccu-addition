package com.ccu.addition.mixin.block;

import com.ccu.addition.feature.NightmarishBlockStore;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 透明试炼方块：活塞推穿。
 *
 * <ul>
 *     <li>{@code moveBlocks} 期间打标记，{@code Level#setBlock} 的拦截靠它判断是不是活塞在动；</li>
 *     <li>{@code isPushable} 放行（按 allowDestroy 处理），这样活塞会直接推掉它；</li>
 *     <li>{@code triggerEvent} 里跳过对它的 {@code removeBlock}，避免推的过程中重复移除。</li>
 * </ul>
 */
@Mixin(PistonBaseBlock.class)
public abstract class PistonBaseBlockMixin {

	@WrapMethod(method = "moveBlocks")
	private boolean ccu$markMoving(Level level, BlockPos pos, Direction facing, boolean extending,
	                              Operation<Boolean> original) {
		boolean previous = NightmarishBlockStore.isMoving();
		NightmarishBlockStore.beginMove();
		try {
			return original.call(level, pos, facing, extending);
		} finally {
			NightmarishBlockStore.endMove(previous);
		}
	}

	@Inject(method = "isPushable", at = @At("RETURN"), cancellable = true)
	private static void ccu$pushable(BlockState state, Level level, BlockPos pos, Direction movementDirection,
	                                boolean allowDestroy, Direction pistonFacing,
	                                CallbackInfoReturnable<Boolean> cir) {
		if (NightmarishBlockStore.matches(state)) {
			cir.setReturnValue(allowDestroy);
		}
	}

	@WrapOperation(
			method = "triggerEvent",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z")
	)
	private boolean ccu$skipRemove(Level level, BlockPos pos, boolean isMoving, Operation<Boolean> original) {
		if (NightmarishBlockStore.matches(level.getBlockState(pos))) {
			return false;
		}
		return original.call(level, pos, isMoving);
	}
}
