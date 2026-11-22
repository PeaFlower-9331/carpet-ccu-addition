package com.ccu.addition.feature;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 1.21.10 的版本差异适配：{@code ResourceKey#location()} 返回 {@code ResourceLocation}。
 *
 * <p>1.21.11 里这个访问器改名为 {@code identifier()}，类型也改名为 {@code Identifier}，
 * 共享源码无法同时写两种名字，因此把这一行放进版本专属源码目录
 * （由 {@code common.gradle} 里 {@code versions/<mcver>/src/main/java} 叠加）。</p>
 */
final class DimensionIds {

	private DimensionIds() {
	}

	static String of(ResourceKey<Level> dimension) {
		return dimension.location().toString();
	}
}
