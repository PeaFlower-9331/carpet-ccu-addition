package com.ccu.addition.command;

import carpet.patches.EntityPlayerMPFake;
import carpet.utils.CommandHelper;
import com.ccu.addition.CcuServer;
import com.ccu.addition.CcuRules;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 批量假人动作。
 *
 * <p>两种挂载方式，共用同一套执行逻辑：</p>
 * <ol>
 *     <li><b>并入 Carpet Org Addition</b>：{@code /playerManager batch action <目标> <动作>}。
 *     由于该附属的命令名允许玩家自定义，这里不硬编码名字，而是在命令树建好后按结构查找
 *     「拥有 {@code batch} 子节点，且 {@code batch} 下有 {@code spawn} 与 {@code kill}」的根节点再挂载；
 *     找不到就安静跳过（例如服务器没装该附属）。</li>
 *     <li><b>独立命令</b>：{@code /ccuplayer <目标> <动作>}，作为没有安装该附属时的等价入口。</li>
 * </ol>
 *
 * <p>动作不自行解析，而是转交 Carpet 的 {@code /player} 命令执行，因此
 * <strong>{@code /player} 支持什么，这里就支持什么</strong>：</p>
 *
 * <pre>
 * /playerManager batch action bot use continuous    // 批量连续右键
 * /playerManager batch action bot jump              // 批量跳一次
 * /playerManager batch action bot jump continuous   // 批量连续跳
 * /playerManager batch action bot attack interval 5 // 批量每 5 刻攻击一次
 * /playerManager batch action all stop              // 全部停下
 * </pre>
 *
 * <p>目标匹配：{@code all} 或 {@code *} 表示全部假人，其它按名字前缀匹配（不区分大小写，
 * {@code bot} 可命中 {@code bot_1}、{@code bot_10}）。只作用于 {@link EntityPlayerMPFake}，不会误控真人。</p>
 */
public final class BatchPlayerActionCommand {

	private static final String ACTION_NODE = "action";

	/**
	 * 不能批量执行的动作：这些是「打开界面」类动作（Carpet Org Addition 扩展给 /player 的），
	 * 一次开一堆背包/末影箱既没有意义，也需要真实的玩家来源。批量时直接跳过并提示。
	 */
	private static final Set<String> NOT_BATCHABLE = Set.of("inventory", "enderChest");

	private BatchPlayerActionCommand() {
	}

	/**
	 * 把 {@code batch action ...} 挂到 Carpet Org Addition 的批量命令下。
	 *
	 * <p>在命令树完全建好之后调用（服务器加载完成 / 资源重载后），避免与其它模组的注册顺序竞争。
	 * Carpet Org 的批量命令结构是 {@code batch <prefix> <start> <end> spawn|kill|drop|trial}，
	 * 我们在 {@code batch} 这一层加一个字面量 {@code action}；Brigadier 解析时字面量优先于参数，
	 * 所以 {@code /playerManager batch action ...} 不会被当作 prefix 吞掉。</p>
	 *
	 * @return 实际挂载到的命令数量（每个别名算一个）
	 */
	public static int attachToBatchCommand(MinecraftServer server) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();

		int attached = 0;
		for (CommandNode<CommandSourceStack> rootChild : dispatcher.getRoot().getChildren()) {
			CommandNode<CommandSourceStack> batch = rootChild.getChild("batch");
			if (batch == null || !looksLikeBatchGroup(batch)) {
				continue;
			}
			if (batch.getChild(ACTION_NODE) == null) {
				batch.addChild(buildActionNode());
				CcuServer.LOGGER.info("已把批量动作并入 /{} batch：/{}/batch {} <目标> <动作>",
						rootChild.getName(), rootChild.getName(), ACTION_NODE);
			}
			attached++;
		}
		return attached;
	}

	/**
	 * 判断这个 {@code batch} 节点是不是 Carpet Org 的批量命令：
	 * 形状为 {@code prefix -> start -> end -> spawn/kill}，靠结构识别，避免硬编码命令名。
	 */
	private static boolean looksLikeBatchGroup(CommandNode<CommandSourceStack> batch) {
		CommandNode<CommandSourceStack> prefix = batch.getChild("prefix");
		if (prefix == null) {
			return false;
		}
		CommandNode<CommandSourceStack> start = prefix.getChild("start");
		if (start == null) {
			return false;
		}
		CommandNode<CommandSourceStack> end = start.getChild("end");
		return end != null && end.getChild("spawn") != null && end.getChild("kill") != null;
	}

	private static LiteralCommandNode<CommandSourceStack> buildActionNode() {
		return Commands.literal(ACTION_NODE)
				.requires(BatchPlayerActionCommand::allowed)
				.executes(BatchPlayerActionCommand::showActions)
				.then(Commands.argument("target", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								suggestions(context.getSource().getServer()), builder))
						.then(Commands.argument("action", StringArgumentType.greedyString())
								.executes(BatchPlayerActionCommand::run)))
				.build();
	}

	/**
	 * 列出当前可用的批量动作。
	 *
	 * <p>列表直接从 Carpet 的 {@code /player} 命令树推导，因此<strong>永远与 {@code /player} 保持一致</strong>：
	 * Carpet 新增或删除动作，这里自动跟着变，不需要改代码。</p>
	 */
	private static int showActions(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<String> actions = collectActions(source.getServer().getCommands().getDispatcher(), source);
		if (actions.isEmpty()) {
			source.sendFailure(Component.literal("找不到 /player 命令，无法列出动作（Carpet 未加载，或规则 commandPlayer 被关闭）"));
			return 0;
		}

		source.sendSuccess(() -> Component.literal("批量可用动作（与 /player 一致，共 " + actions.size() + " 个）："), false);
		for (String action : actions) {
			source.sendSuccess(() -> Component.literal(" - " + action), false);
		}
		source.sendSuccess(() -> Component.literal("用法：/ccuplayer <目标> <动作>，例：/ccuplayer bot use continuous"), false);
		return actions.size();
	}

	/**
	 * 从 {@code /player} 的命令树里收集动作名与它们的一级子参数。
	 *
	 * <p>会按 {@code requires} 过滤：Carpet Org Addition 给 {@code /player} 追加的动作
	 * （{@code inventory}、{@code teleport} 等）由它自己的规则控制开关，关着的时候解析不到，
	 * 这里也就不列出来，保证「列出来的都能用」。</p>
	 */
	private static List<String> collectActions(CommandDispatcher<CommandSourceStack> dispatcher, CommandSourceStack source) {
		CommandNode<CommandSourceStack> player = dispatcher.getRoot().getChild("player");
		if (player == null) {
			return List.of();
		}

		// /player 的直接子节点是「名字」参数，动作都在它下面
		CommandNode<CommandSourceStack> nameArgument = player.getChild("player");
		if (nameArgument == null) {
			return List.of();
		}

		List<String> result = new ArrayList<>();
		for (CommandNode<CommandSourceStack> action : nameArgument.getChildren()) {
			if (!(action instanceof LiteralCommandNode<CommandSourceStack>)) {
				continue;
			}
			if (NOT_BATCHABLE.contains(action.getName())) {
				continue;
			}
			if (!action.getRequirement().test(source)) {
				// 被规则关闭的动作（例如 Carpet Org 的 playerCommand 系列开关）
				continue;
			}
			if (action.getChildren().isEmpty()) {
				result.add(action.getName());
				continue;
			}
			List<String> children = new ArrayList<>();
			for (CommandNode<CommandSourceStack> child : action.getChildren()) {
				children.add(child instanceof LiteralCommandNode<CommandSourceStack>
						? child.getName()
						: "<" + child.getName() + ">");
			}
			result.add(action.getName() + " [" + String.join("|", children) + "]");
		}
		return result;
	}

	private static boolean allowed(CommandSourceStack source) {
		return CommandHelper.canUseCommand(source, CcuRules.commandBatchPlayerAction);
	}

	private static int run(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();
		String target = StringArgumentType.getString(context, "target");
		String action = StringArgumentType.getString(context, "action").strip();

		if (action.isEmpty()) {
			source.sendFailure(Component.literal("动作不能为空，例如：use continuous、jump、stop"));
			return 0;
		}

		String head = action.split("\\s+", 2)[0];
		if (NOT_BATCHABLE.contains(head)) {
			source.sendFailure(Component.literal("动作 " + head + " 是「打开界面」类动作，不支持批量执行（可对单个假人使用 /player <名字> " + head + "）"));
			return 0;
		}

		List<ServerPlayer> bots = server.getPlayerList().getPlayers().stream()
				.filter(player -> player instanceof EntityPlayerMPFake)
				.filter(player -> matches(target, player.getScoreboardName()))
				.toList();

		if (bots.isEmpty()) {
			source.sendFailure(Component.literal("没有匹配的假人：" + target));
			return 0;
		}

		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		// 抑制 /player 每条命令的回显，避免批量操作刷屏
		CommandSourceStack silent = source.withSuppressedOutput();

		int success = 0;
		for (ServerPlayer bot : bots) {
			String command = "player " + bot.getScoreboardName() + " " + action;
			try {
				dispatcher.execute(command, silent);
				success++;
			} catch (CommandSyntaxException e) {
				source.sendFailure(Component.literal(
						"动作执行失败：" + command + "（" + e.getMessage()
								+ "；该动作可能不存在，或被相关规则关闭：Carpet 的 commandPlayer、Carpet Org 的动作开关等）"));
				return success;
			}
		}

		final int count = success;
		source.sendSuccess(() -> Component.literal("已对 " + count + " 个假人执行：/player <名字> " + action), true);
		return count;
	}

	private static boolean matches(String target, String playerName) {
		if (target.equals("*") || target.equalsIgnoreCase("all")) {
			return true;
		}
		return playerName.toLowerCase(Locale.ROOT).startsWith(target.toLowerCase(Locale.ROOT));
	}

	private static List<String> suggestions(MinecraftServer server) {
		List<String> names = new ArrayList<>();
		names.add("all");
		server.getPlayerList().getPlayers().stream()
				.filter(player -> player instanceof EntityPlayerMPFake)
				.map(ServerPlayer::getScoreboardName)
				.sorted()
				.forEach(names::add);
		return names;
	}
}
