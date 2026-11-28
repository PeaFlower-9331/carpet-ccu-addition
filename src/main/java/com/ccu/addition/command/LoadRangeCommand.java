package com.ccu.addition.command;

import carpet.patches.EntityPlayerMPFake;
import carpet.utils.CommandHelper;
import com.ccu.addition.CcuServer;
import com.ccu.addition.CcuRules;
import com.ccu.addition.feature.FakePlayerChunkLoader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;

/**
 * 假人加载范围的命令。
 *
 * <p>主要入口并入 Carpet 的 {@code /player}：</p>
 * <pre>
 * /player bot1 load 16       // 把 bot1 的加载范围调到 16 区块
 * /player bot1 load          // 查看 bot1 当前的设置
 * /player bot1 load default  // 恢复默认（跟随服务器视距）
 * </pre>
 *
 * <p>本模组不注册独立命令：Carpet 没加载时 {@code /player} 命令树不存在，功能自然也不可用。</p>
 *
 * <p>设置按名字保存，假人重新召唤后依然生效；未设置的假人不额外加载，行为与原版一致。
 * 本功能<strong>只能调大</strong>，半径不大于服务器视距时不会有额外效果。</p>
 */
public final class LoadRangeCommand {

	private static final String LOAD_NODE = "load";

	private LoadRangeCommand() {
	}

	/**
	 * 把 {@code load} 子命令并入 Carpet 的 {@code /player <名字> ...} 动作列表。
	 *
	 * <p>在命令树完全建好之后调用（服务器加载完成 / 资源重载后），避免与 Carpet 注册顺序竞争。
	 * 挂在「名字参数」下面而不是 {@code /player} 根字面量下，避免和名为 {@code load} 的假人重名冲突。</p>
	 *
	 * @return 是否挂载成功
	 */
	public static int attachToPlayerCommand(MinecraftServer server) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		CommandNode<CommandSourceStack> player = dispatcher.getRoot().getChild("player");
		if (player == null) {
			return 0;
		}
		CommandNode<CommandSourceStack> nameArgument = player.getChild("player");
		if (nameArgument == null) {
			return 0;
		}
		if (nameArgument.getChild(LOAD_NODE) == null) {
			nameArgument.addChild(buildLoadNode());
			CcuServer.LOGGER.info("已把加载范围并入 /player：/player <名字> {} <半径|default>", LOAD_NODE);
		}
		return 1;
	}

	private static LiteralCommandNode<CommandSourceStack> buildLoadNode() {
		return Commands.literal(LOAD_NODE)
				.requires(LoadRangeCommand::allowed)
				.executes(LoadRangeCommand::querySelf)
				.then(Commands.argument("radius", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(RADIUS_SUGGESTIONS, builder))
						.executes(LoadRangeCommand::setSelf))
				.build();
	}

	private static boolean allowed(CommandSourceStack source) {
		return CommandHelper.canUseCommand(source, CcuRules.commandPlayerLoadRange);
	}

	// ---------------------------------------------------------------- 执行

	/**
	 * {@code /player <名字> load}
	 */
	private static int querySelf(CommandContext<CommandSourceStack> context) {
		return query(context.getSource(), StringArgumentType.getString(context, "player"));
	}

	/**
	 * {@code /player <名字> load <半径|default>}
	 */
	private static int setSelf(CommandContext<CommandSourceStack> context) {
		return apply(context.getSource(),
				StringArgumentType.getString(context, "player"),
				StringArgumentType.getString(context, "radius"));
	}

	private static int query(CommandSourceStack source, String name) {
		int radius = FakePlayerChunkLoader.getRange(name);
		if (radius <= 0) {
			source.sendSuccess(() -> Component.literal(name + "：未设置额外加载范围（跟随服务器视距 " 
					+ source.getServer().getPlayerList().getViewDistance() + " 区块）"), false);
		} else {
			source.sendSuccess(() -> Component.literal(name + "：额外加载 " + radius + " 区块"), false);
		}
		return radius;
	}

	private static int apply(CommandSourceStack source, String target, String raw) {
		int radius;
		if (raw.equalsIgnoreCase("default") || raw.equals("-1") || raw.equals("0")) {
			// default / 0 / -1 都表示清除，恢复成跟随服务器视距
			radius = 0;
		} else {
			try {
				radius = Integer.parseInt(raw);
			} catch (NumberFormatException e) {
				source.sendFailure(Component.literal("半径必须是整数或 default：" + raw));
				return 0;
			}
			if (radius < 1 || radius > FakePlayerChunkLoader.MAX_RADIUS) {
				source.sendFailure(Component.literal("半径需在 1-" + FakePlayerChunkLoader.MAX_RADIUS
						+ " 之间，或用 default / 0 / -1 清除：" + radius));
				return 0;
			}
		}

		int previous = FakePlayerChunkLoader.setRange(target, radius);
		boolean online = source.getServer().getPlayerList().getPlayerByName(target) instanceof EntityPlayerMPFake;
		int viewDistance = source.getServer().getPlayerList().getViewDistance();

		if (radius <= 0) {
			source.sendSuccess(() -> Component.literal("已恢复 " + target + " 的默认加载范围（跟随服务器视距"
					+ (previous > 0 ? "，之前是 " + previous + " 区块" : "") + "）"), true);
		} else if (radius <= viewDistance) {
			// 本功能只能调大：小于等于服务器视距时原版 ticket 依然覆盖，加不加都一样
			source.sendSuccess(() -> Component.literal("已记录 " + target + " 的加载范围为 " + radius
					+ " 区块，但它不大于服务器视距（" + viewDistance + "），实际不会有额外效果"
					+ "（本功能只能调大，无法缩小）"), true);
		} else {
			source.sendSuccess(() -> Component.literal("已设置 " + target + " 的额外加载范围为 " + radius + " 区块"
					+ (online ? "（已生效）" : "（该假人未在线，召唤后自动生效）")), true);
		}

		// 立即对账一次，不必等下一个 20 刻的检查周期
		FakePlayerChunkLoader.applyNow(source.getServer());
		return 1;
	}

	private static final List<String> RADIUS_SUGGESTIONS =
			List.of("default", "8", "12", "16", "24", "32");
}
