package com.ccu.addition.mixin.level;

import com.ccu.addition.feature.NightmarishBlockStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 透明试炼方块：活塞把方块替换掉时登记它的状态，等稳定后复原。
 *
 * <p>只在活塞动画期间（{@link NightmarishBlockStore#isMoving()}）才去查方块状态，
 * 平时这条拦截只有一次布尔判断，不会给大规模改方块的机器增加开销。</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin {

	@Inject(
			method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At("HEAD")
	)
	private void ccu$capturePushedBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft,
	                                    CallbackInfoReturnable<Boolean> cir) {
		if (NightmarishBlockStore.isMoving()) {
			NightmarishBlockStore.capture((Level) (Object) this, pos, newState);
		}
	}
}
