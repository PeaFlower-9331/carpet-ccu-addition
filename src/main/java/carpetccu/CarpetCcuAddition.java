package carpetccu;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Mod 入口。
 *
 * <p>这里只做两件事：拿到本模组的版本号，然后把 {@link CarpetCcuServer} 注册进 Carpet。
 * 具体的规则解析、命令注册、生命周期回调都交给 Carpet 的扩展机制处理。</p>
 */
public class CarpetCcuAddition implements ModInitializer {

	public static final String MOD_ID = "carpet_ccu_addition";

	private static String version = "dev";

	/**
	 * @return 本模组的版本号，取自 fabric.mod.json
	 */
	public static String getVersion() {
		return version;
	}

	@Override
	public void onInitialize() {
		version = FabricLoader.getInstance()
				.getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");

		CarpetCcuServer.init();

		CarpetCcuServer.LOGGER.info("Carpet CCU Addition {} 已初始化", version);
	}
}
