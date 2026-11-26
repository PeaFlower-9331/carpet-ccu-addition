package com.ccu.addition;

import carpet.CarpetExtension;
import carpet.CarpetServer;
import carpet.api.settings.Rule;
import carpet.utils.Translations;
import com.ccu.addition.command.BatchPlayerActionCommand;
import com.ccu.addition.command.LoadRangeCommand;
import com.ccu.addition.feature.FakePlayerChunkLoader;
import com.ccu.addition.feature.FakePlayerRideRestore;
import com.ccu.addition.feature.MinecartChunkLoader;
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
public class CcuServer implements CarpetExtension {

	public static final Logger LOGGER = LoggerFactory.getLogger("CarpetCCU");

	private static final CcuServer INSTANCE = new CcuServer();

	/**
	 * 批量动作是否已成功并入 Carpet Org 的批量命令（避免每刻重复尝试）。
	 */
	private boolean commandsAttached = false;

	/**
	 * 加载范围是否已并入 Carpet 的 /player 命令。
	 */
	private boolean loadRangeAttached = false;


	/**
	 * 挂载尝试次数上限，避免没装 Carpet Org 时每刻重复扫描命令树。
	 */
	private int attachAttempts = 0;

	private CcuServer() {
	}

	public static void init() {
		CarpetServer.manageExtension(INSTANCE);
		debug("扩展已注册到 Carpet");
	}

	// ---------------------------------------------------------------- 生命周期

	@Override
	public void onGameStarted() {
		// 把本模组的规则解析进 /carpet
		CcuRuleRegistrar.init();
		LOGGER.info("Carpet CCU Addition {}：已注册 {} 条规则", CcuMod.getVersion(), countRules());
	}

	@Override
	public void onServerLoaded(MinecraftServer server) {
		debug("服务器已加载");
		// 先挂 /player <名字> load，再把 /player 的动作整棵复制到 Carpet Org 的批量命令下：
		// 顺序反了会漏掉 load 这个动作
		loadRangeAttached = LoadRangeCommand.attachToPlayerCommand(server) > 0;
		commandsAttached = BatchPlayerActionCommand.attachToBatchCommand(server) > 0;
		// 读取假人加载范围配置（按名字保存，重新召唤后依然生效）
		FakePlayerChunkLoader.load();
		// 读取矿车锚点：重启后先把这些矿车所在区块加载起来，否则扫不到实体、加载器会一直空转
		MinecartChunkLoader.loadAnchors();
	}

	@Override
	public void onServerLoadedWorlds(MinecraftServer server) {
		debug("世界已加载");
	}

	@Override
	public void onTick(MinecraftServer server) {
		// 每游戏刻最先执行一次。后续按刻处理的功能写在这里，
		// 注意它每 tick 都会跑，不要在这里做重活。
		if (!commandsAttached && attachAttempts < 3) {
			// 命令树可能晚于 onServerLoaded 建好，这里兜底重试几次（没装 Carpet Org 时不做无谓尝试）
			attachAttempts++;
			if (!loadRangeAttached) {
				loadRangeAttached = LoadRangeCommand.attachToPlayerCommand(server) > 0;
			}
			commandsAttached = BatchPlayerActionCommand.attachToBatchCommand(server) > 0;
		}
		// 假人加载范围：按配置给假人维持区块 ticket（内部每 20 刻对账一次）
		FakePlayerChunkLoader.tick(server);
		// 假人重新上线后回到原来的载具（GCA 假人驻留 / TIS rejoin）
		FakePlayerRideRestore.tick(server);
		// 矿车加载器：每 5 秒给符合条件的矿车续一次加载票
		MinecartChunkLoader.tick(server);
	}

	@Override
	public void onPlayerLoggedIn(ServerPlayer player) {
		debug("玩家登录：%s", player.getScoreboardName());
		// 假人重新上线（GCA 假人驻留 / TIS rejoin）后回到原来的载具
		FakePlayerRideRestore.onLogin(player);
	}

	@Override
	public void onPlayerLoggedOut(ServerPlayer player) {
		debug("玩家登出：%s", player.getScoreboardName());
		// 假人下线时立刻撤掉它的额外加载 ticket
		if (player.level() instanceof net.minecraft.server.level.ServerLevel level) {
			FakePlayerChunkLoader.forget(level.getServer(), player.getUUID());
		}
		FakePlayerRideRestore.forget(player.getUUID());
	}

	@Override
	public void onServerClosed(MinecraftServer server) {
		debug("服务器关闭");
		// 关服前把矿车锚点落盘，避免最后几轮的变更丢失
		MinecartChunkLoader.saveAnchors();
	}

	@Override
	public void onReload(MinecraftServer server) {
		debug("服务器 /reload");
		// 资源重载会重建命令树，需要重新挂载（顺序同 onServerLoaded）
		LoadRangeCommand.attachToPlayerCommand(server);
		BatchPlayerActionCommand.attachToBatchCommand(server);
	}

	// ---------------------------------------------------------------- 元信息

	@Override
	public String version() {
		return CcuMod.getVersion();
	}

	@Override
	public Map<String, String> canHasTranslations(String lang) {
		return Translations.getTranslationFromResourcePath("assets/carpet-ccu-addition/lang/%s.json".formatted(lang));
	}

	// ---------------------------------------------------------------- 工具

	/**
	 * 仅在规则 debugLog 打开时输出的日志。
	 *
	 * <p>占位符用 {@link String#formatted} 的 printf 风格（{@code %s}、{@code %d}），
	 * 与调用处保持一致。注意不能直接把 format + args 交给 slf4j：slf4j 认的是
	 * {@code {}}，printf 占位符会被当成字面量原样打印出来。</p>
	 */
	public static void debug(String format, Object... args) {
		if (CcuRules.debugLog) {
			LOGGER.info(args.length == 0 ? format : format.formatted(args));
		}
	}

	/**
	 * 统计 {@link CcuRules} 里带 {@link Rule} 注解的字段数量，用于启动日志自检。
	 */
	private static int countRules() {
		int count = 0;
		for (Field field : CcuRules.class.getDeclaredFields()) {
			if (field.isAnnotationPresent(Rule.class)) {
				count++;
			}
		}
		return count;
	}
}
