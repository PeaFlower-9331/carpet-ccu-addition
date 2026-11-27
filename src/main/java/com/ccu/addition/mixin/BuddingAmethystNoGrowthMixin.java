package com.ccu.addition.mixin;

import com.ccu.addition.CcuRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BuddingAmethystBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 透明试炼方块：规则一旦开启（不是 {@code false}），紫水晶母岩不再生长新芽。
 */
@Mixin(BuddingAmethystBlock.class)
public abstract class BuddingAmethystNoGrowthMixin {

	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true)
	private void ccu$stopGrowth(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
	                            CallbackInfo ci) {
		if (CcuRules.betaTransparentNightmarishBlock != CcuRules.NightmarishMode.FALSE) {
			ci.cancel();
		}
	}
}
