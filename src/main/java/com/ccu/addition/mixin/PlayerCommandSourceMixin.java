package com.ccu.addition.mixin;

import carpet.commands.PlayerCommand;
import com.ccu.addition.feature.PreloadStatus;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 记录 {@code /player <名字> spawn} 的执行者，供预加载状态提示使用。
 *
 * <p>Carpet 的 {@code createFake} 拿不到命令来源，只能在这里把当前线程上的执行者记下来；
 * 真正的预加载拦截发生在 {@code createFake} 开头（同一线程），取走即可。</p>
 */
@Mixin(PlayerCommand.class)
public abstract class PlayerCommandSourceMixin {

	@Inject(method = "spawn", at = @At("HEAD"), remap = false)
	private static void ccu$captureSpawnSource(CommandContext<CommandSourceStack> context,
	                                           CallbackInfoReturnable<Integer> cir) {
		PreloadStatus.enterSource(context.getSource().getPlayer());
	}

	@Inject(method = "spawn", at = @At("RETURN"), remap = false)
	private static void ccu$clearSpawnSource(CommandContext<CommandSourceStack> context,
	                                         CallbackInfoReturnable<Integer> cir) {
		PreloadStatus.clearSource();
	}
}
