package com.ccu.addition.mixin;

import com.ccu.addition.CcuRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 规则 {@code noAmethystBudInWater}：紫水晶母岩不在相邻方格是水的面上生长紫水晶芽。
 *
 * <p>原版 {@code canClusterGrowAtState} 对「空气」和「水源方块」都返回 true，
 * 所以母岩会在水里长芽。这里在判定阶段直接对水返回 false：只要目标位置是水，
 * 该方向就不会生成芽——已经长出来的芽/簇不受影响。</p>
 */
@Mixin(BuddingAmethystBlock.class)
public abstract class BuddingAmethystBlockMixin {

	@Inject(method = "canClusterGrowAtState", at = @At("HEAD"), cancellable = true)
	private static void ccu$noGrowthInWater(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (!CcuRules.noAmethystBudInWater) {
			return;
		}
		if (state.is(Blocks.WATER)) {
			cir.setReturnValue(false);
		}
	}
}
