package com.ccu.addition.mixin.block;

import com.ccu.addition.feature.NightmarishBlockStore;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 透明试炼方块：抗爆。
 *
 * <p>直接把抗爆值拉到 1200（TNT 是 4，黑曜石 1200），普通爆炸炸不掉。</p>
 */
@Mixin(Block.class)
public abstract class BlockMixin {

	@Inject(method = "getExplosionResistance", at = @At("HEAD"), cancellable = true)
	private void ccu$blastResistance(CallbackInfoReturnable<Float> cir) {
		if (NightmarishBlockStore.matchesBlock((Block) (Object) this)) {
			cir.setReturnValue(1200.0F);
		}
	}
}
