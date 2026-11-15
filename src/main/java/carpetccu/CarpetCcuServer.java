package carpetccu;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import carpet.api.settings.Rule;
import carpet.utils.Translations;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Carpet 扩展实现。
 *
 * <p>实现 {@link CarpetExtension} 并调用 {@link CarpetServer#manageExtension(CarpetExtension)}
 * 之后，Carpet 会在服务器生命周期的相应节点回调下面这些方法。</p>
 *
 * <p>注意：1.21.11 分支里 Carpet 的扩展接口位于 {@code carpet.CarpetExtension}
 * （更早的版本是 {@code carpet.api.CarpetExtension}），移植到旧版本时这里的 import 需要跟着改。</p>
 */
public class CarpetCcuServer implements CarpetExtension {

	public static final Logger LOGGER = LoggerFactory.getLogger("CarpetCCU");

	private static final CarpetCcuServer INSTANCE = new CarpetCcuServer();

	private CarpetCcuServer() {
	}

	public static void init() {
		CarpetServer.manageExtension(INSTANCE);
		debug("扩展已注册到 Carpet");
	}

	// ---------------------------------------------------------------- 生命周期

	@Override
	public void onGameStarted() {
		// 把本模组的规则解析进 /carpet
		CcuSettings.init();
		LOGGER.info("Carpet CCU Addition {}：已注册 {} 条规则", CarpetCcuAddition.getVersion(), countRules());
	}

	@Override
	public void onServerLoaded(MinecraftServer server) {
		debug("服务器已加载");
	}

	@Override
	public void onServerLoadedWorlds(MinecraftServer server) {
		debug("世界已加载");
	}

	@Override
	public void onTick(MinecraftServer server) {
		// 每游戏刻最先执行一次。后续按刻处理的功能写在这里，
		// 注意它每 tick 都会跑，不要在这里做重活。
	}

	@Override
	public void onPlayerLoggedIn(ServerPlayer player) {
		debug("玩家登录：%s", player.getScoreboardName());
	}

	@Override
	public void onPlayerLoggedOut(ServerPlayer player) {
		debug("玩家登出：%s", player.getScoreboardName());
	}

	@Override
	public void onServerClosed(MinecraftServer server) {
		debug("服务器关闭");
	}

	@Override
	public void onReload(MinecraftServer server) {
		debug("服务器 /reload");
	}

	// ---------------------------------------------------------------- 元信息

	@Override
	public String version() {
		return CarpetCcuAddition.getVersion();
	}

	@Override
	public Map<String, String> canHasTranslations(String lang) {
		return Translations.getTranslationFromResourcePath("assets/carpet_ccu/lang/%s.json".formatted(lang));
	}

	// ---------------------------------------------------------------- 工具

	/**
	 * 仅在规则 ccuDebugLog 打开时输出的日志。
	 */
	public static void debug(String format, Object... args) {
		if (CarpetCcuSettings.ccuDebugLog) {
			LOGGER.info(format, args);
		}
	}

	/**
	 * 统计 {@link CarpetCcuSettings} 里带 {@link Rule} 注解的字段数量，用于启动日志自检。
	 */
	private static int countRules() {
		int count = 0;
		for (Field field : CarpetCcuSettings.class.getDeclaredFields()) {
			if (field.isAnnotationPresent(Rule.class)) {
				count++;
			}
		}
		return count;
	}
}
