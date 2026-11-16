package com.ccu.addition;

import carpet.CarpetServer;

/**
 * 规则注册入口。
 *
 * <p>这里把 {@link CcuRules} 里的规则解析进 Carpet 的<strong>主设置管理器</strong>，
 * 于是所有规则都出现在 {@code /carpet} 命令下（{@code /carpet list CCU} 可只看本模组的规则），
 * 配置也统一落在存档的 {@code carpet.conf} 中 —— 这与 Carpet TIS Addition、Carpet CuO Addition
 * 等主流附属的做法一致，服务器管理员不需要额外记一个命令。</p>
 *
 * <p>如果以后想把规则独立到 {@code /ccu} 命令与 {@code ccu.conf}，只需把 {@link #init()}
 * 换成自己的 {@code SettingsManager} 实例，并在 {@code CarpetExtension#extensionSettingsManager()}
 * 里返回它，同时把规则名去掉 {@code ccu} 前缀即可。</p>
 */
public final class CcuRuleRegistrar {

	private static boolean parsed = false;

	private CcuRuleRegistrar() {
	}

	/**
	 * 把规则类解析进主设置管理器，多次调用只会生效一次。
	 */
	public static synchronized void init() {
		if (parsed) {
			return;
		}
		CarpetServer.settingsManager.parseSettingsClass(CcuRules.class);
		parsed = true;
	}
}
