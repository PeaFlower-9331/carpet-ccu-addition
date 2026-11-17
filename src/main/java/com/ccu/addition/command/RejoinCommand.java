package com.ccu.addition.command;

import carpet.utils.CommandHelper;
import com.ccu.addition.CcuRules;
import com.ccu.addition.CcuServer;
import com.ccu.addition.feature.RejoinState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * 把 {@code rejoin} 子命令并入 Carpet 的 {@code /player <名字> ...}。
 *
 * <pre>
 * /player bot1 rejoin
 * </pre>
 *
 * <p>行为参考 Carpet TIS Addition 的同名命令：以<strong>存档位置</strong>重新召唤假人，
 * 而不是像 {@code spawn} 那样传送到命令执行点；如果假人下线时正坐在载具上，还会保持骑乘
 * （由规则 {@code fakePlayerKeepVehicle} 控制）。</p>
 *
 * <p>实现上不动 Carpet 的 {@code spawn}：置位 {@link RejoinState} 标记后直接复用
 * {@code /player <名字> spawn}，让 mixin 在召唤流程里按标记跳过位置修正与下坐骑。</p>
 */
public final class RejoinCommand {

	private static final String NODE = "rejoin";

	private RejoinCommand() {
	}

	/**
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
		if (nameArgument.getChild(NODE) == null) {
			nameArgument.addChild(buildNode());
			CcuServer.LOGGER.info("已把 rejoin 并入 /player：/player <名字> {}", NODE);
		}
		return 1;
	}

	private static LiteralCommandNode<CommandSourceStack> buildNode() {
		return Commands.literal(NODE)
				.requires(RejoinCommand::allowed)
				.executes(RejoinCommand::rejoin)
				.build();
	}

	private static boolean allowed(CommandSourceStack source) {
		return CommandHelper.canUseCommand(source, CcuRules.commandPlayerRejoin);
	}

	private static int rejoin(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String name = StringArgumentType.getString(context, "player");

		RejoinState.begin();
		try {
			return source.getServer().getCommands().getDispatcher()
					.execute("player " + name + " spawn", source);
		} catch (CommandSyntaxException e) {
			source.sendFailure(Component.literal(e.getMessage()));
			return 0;
		} finally {
			RejoinState.end();
		}
	}
}
