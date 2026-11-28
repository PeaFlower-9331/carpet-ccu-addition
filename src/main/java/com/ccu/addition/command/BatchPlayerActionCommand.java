package com.ccu.addition.command;

import carpet.patches.EntityPlayerMPFake;
import carpet.utils.CommandHelper;
import com.ccu.addition.CcuServer;
import com.ccu.addition.CcuRules;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.ParsedCommandNode;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 批量假人动作。
 *
 * <p>不使用独立的 {@code action} 参数，而是把 Carpet {@code /player <名字> <动作>} 下的每个动作
 * <strong>复制一份</strong>挂到 Carpet Org Addition 批量命令的 {@code <end>} 节点下，和它自带的
 * {@code spawn} / {@code kill} / {@code drop} / {@code trial} 并列：</p>
 *
 * <pre>
 * /playerManager batch &lt;前缀&gt; &lt;起始&gt; &lt;结束&gt; &lt;动作&gt; [动作参数...]
 *
 * /playerManager batch bot 1 45 stop
 * /playerManager batch bot 1 45 use continuous
 * /playerManager batch bot 1 45 attack interval 5
 * /playerManager batch bot 1 45 look north
 * </pre>
 *
 * <p>因为挂的是真实节点，<strong>Tab 补全完全交给 Brigadier</strong>：动作名、动作的子参数
 * （{@code use once|continuous|interval}、{@code look north|south|...}、{@code attack interval <ticks>}）
 * 都能补，和打 {@code /player <名字> } 时一模一样，不需要额外维护补全列表。</p>
 *
 * <p>目标解析沿用 Carpet Org 的语义：名字是「前缀 + 数字」，前缀没写下划线会自动补一个，
 * 所以 {@code batch bot 1 45} 对应 {@code bot_1} … {@code bot_45}；同时也接受不带下划线的
 * {@code bot1}，以及带 TIS / GCA 名字前缀的写法（按后缀匹配，但要求前缀以非字母数字结尾）。
 * {@code all} 或 {@code *} 表示所有假人。</p>
 *
 * <p>与 Carpet Org 自带的 {@code spawn} / {@code kill} / {@code drop} / {@code trial} 重名时不覆盖，
 * 保留它自己的实现（{@code kill} 会走它的下班消息与分批任务）。</p>
 */
public final class BatchPlayerActionCommand {

	/**
	 * 不能批量执行的动作：这些是「打开界面」类动作（Carpet Org Addition 扩展给 /player 的），
	 * 一次开一堆背包/末影箱既没有意义，也需要真实的玩家来源。批量时直接跳过。
	 */
	private static final Set<String> NOT_BATCHABLE = Set.of("inventory", "enderChest");

	/**
	 * 一次批量最多覆盖多少个编号。
	 *
	 * <p>目标解析是「每个玩家 × 区间长度」的双层循环，区间开到上亿会把主线程卡死；
	 * 这里给个远大于实际用量的上限（几千个假人已经不可能了）。</p>
	 */
	private static final int MAX_TARGET_SPAN = 10_000;

	/**
	 * 批量目标里代表「所有假人」的前缀。
	 */
	private static final Set<String> ALL_TARGETS = Set.of("*", "all");

	private BatchPlayerActionCommand() {
	}

	// ---------------------------------------------------------------- 挂载

	/**
	 * 把 {@code /player} 的每个动作挂到 Carpet Org Addition 的批量命令下。
	 *
	 *
	 * @return 实际挂载到的命令数量（命令有多个别名时每个算一个）
	 */
	public static int attachToBatchCommand(MinecraftServer server) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		CommandNode<CommandSourceStack> actions = playerActionRoot(dispatcher);
		if (actions == null) {
			// /player 命令不存在（Carpet 未加载或 commandPlayer 被关掉），没什么可挂的
			return 0;
		}

		int attached = 0;
		for (CommandNode<CommandSourceStack> rootChild : dispatcher.getRoot().getChildren()) {
			CommandNode<CommandSourceStack> batch = rootChild.getChild("batch");
			if (batch == null || !looksLikeBatchGroup(batch)) {
				continue;
			}
			CommandNode<CommandSourceStack> group = batch.getChild("prefix").getChild("start").getChild("end");

			int added = 0;
			for (CommandNode<CommandSourceStack> action : actions.getChildren()) {
				if (!(action instanceof LiteralCommandNode<CommandSourceStack> literal)) {
					continue;
				}
				String name = literal.getName();
				if (NOT_BATCHABLE.contains(name)) {
					continue;
				}
				// Carpet Org 自己已经有 spawn / kill / drop / trial，保留它的实现
				if (group.getChild(name) != null) {
					continue;
				}
				group.addChild(copyForBatch(literal));
				added++;
			}
			if (added > 0) {
				CcuServer.LOGGER.info("已把 {} 个批量动作并入 /{} batch：/{} batch <前缀> <起始> <结束> <动作>",
						added, rootChild.getName(), rootChild.getName());
				attached++;
			}
		}
		return attached;
	}

	/**
	 * 取 {@code /player <名字>} 这个节点，动作都是它的子节点。
	 */
	private static CommandNode<CommandSourceStack> playerActionRoot(CommandDispatcher<CommandSourceStack> dispatcher) {
		CommandNode<CommandSourceStack> player = dispatcher.getRoot().getChild("player");
		if (player == null) {
			return null;
		}
		return player.getChild("player");
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

	/**
	 * 深拷贝一个动作节点（含全部子节点），把可以执行的位置换成批量执行。
	 *
	 * <p>保留原来的 {@code requires}、参数类型与自定义补全，所以补全行为与 {@code /player} 完全一致。</p>
	 */
	private static CommandNode<CommandSourceStack> copyForBatch(CommandNode<CommandSourceStack> node) {
		if (node instanceof LiteralCommandNode<CommandSourceStack> literal) {
			LiteralArgumentBuilder<CommandSourceStack> builder = Commands.literal(literal.getName());
			builder.requires(literal.getRequirement());
			if (literal.getCommand() != null) {
				builder.executes(BatchPlayerActionCommand::runBatch);
			}
			for (CommandNode<CommandSourceStack> child : literal.getChildren()) {
				builder.then(copyForBatch(child));
			}
			return builder.build();
		}
		if (node instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
			return copyArgument(argument);
		}
		return null;
	}

	private static <T> CommandNode<CommandSourceStack> copyArgument(ArgumentCommandNode<CommandSourceStack, T> argument) {
		RequiredArgumentBuilder<CommandSourceStack, T> builder = Commands.argument(argument.getName(), argument.getType());
		builder.requires(argument.getRequirement());
		SuggestionProvider<CommandSourceStack> suggestions = argument.getCustomSuggestions();
		if (suggestions != null) {
			builder.suggests(suggestions);
		}
		if (argument.getCommand() != null) {
			builder.executes(BatchPlayerActionCommand::runBatch);
		}
		for (CommandNode<CommandSourceStack> child : argument.getChildren()) {
			builder.then(copyForBatch(child));
		}
		return builder.build();
	}

	// ---------------------------------------------------------------- 执行

	/**
	 * 对匹配到的所有假人执行同一个动作。
	 */
	private static int runBatch(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();

		if (!CommandHelper.canUseCommand(source, CcuRules.commandBatchPlayerAction)) {
			source.sendFailure(Component.literal("没有权限使用批量假人动作"));
			return 0;
		}

		String action = actionText(context);
		if (action == null || action.isEmpty()) {
			source.sendFailure(Component.literal("没有解析到动作，请用 Tab 补全查看可用动作"));
			return 0;
		}

		String prefix = StringArgumentType.getString(context, "prefix");
		int start = IntegerArgumentType.getInteger(context, "start");
		int end = IntegerArgumentType.getInteger(context, "end");
		if (!ALL_TARGETS.contains(prefix.toLowerCase(Locale.ROOT))
				&& Math.abs((long) end - start) + 1 > MAX_TARGET_SPAN) {
			source.sendFailure(Component.literal("批量区间过大：一次最多 " + MAX_TARGET_SPAN + " 个编号"));
			return 0;
		}

		List<ServerPlayer> bots = resolveTargets(server, context);
		if (bots.isEmpty()) {
			source.sendFailure(Component.literal("没有匹配的假人：" + describeTarget(context)));
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
			} catch (Exception e) {
				source.sendFailure(Component.literal("动作执行失败：" + command + "（" + e.getMessage()
						+ "；该动作可能被相关规则关闭：Carpet 的 commandPlayer、Carpet Org 的动作开关等）"));
				break;
			}
		}

		final int count = success;
		if (count > 0) {
			source.sendSuccess(() -> Component.literal("已对 " + count + " 个假人执行：/player <名字> " + action), true);
		}
		return count;
	}

	/**
	 * 从玩家实际输入里截出动作部分。
	 *
	 * <p>不复用解析出来的节点名去拼，而是按 {@code <end>} 之后第一个动作节点的起始位置到整条命令的末尾
	 * 取原文，这样参数的大小写、引号、坐标写法都原样保留。</p>
	 */
	private static String actionText(CommandContext<CommandSourceStack> context) {
		List<ParsedCommandNode<CommandSourceStack>> nodes = context.getNodes();
		int endIndex = -1;
		for (int i = 0; i < nodes.size(); i++) {
			if ("end".equals(nodes.get(i).getNode().getName())) {
				endIndex = i;
			}
		}
		if (endIndex < 0 || endIndex + 1 >= nodes.size()) {
			return null;
		}
		int from = nodes.get(endIndex + 1).getRange().getStart();
		int to = context.getRange().getEnd();
		return context.getInput().substring(from, to).strip();
	}

	/**
	 * 按 Carpet Org 的「前缀 + 数字」语义解析目标假人。
	 *
	 * <p>先把区间展开成候选名字集合（最多 {@value #MAX_TARGET_SPAN} 个），再逐个假人比对；
	 * 这样开销是「区间长度 + 假人数 × 名字长度」，不随两者相乘增长。</p>
	 */
	private static List<ServerPlayer> resolveTargets(MinecraftServer server, CommandContext<CommandSourceStack> context) {
		String prefix = StringArgumentType.getString(context, "prefix");
		boolean all = ALL_TARGETS.contains(prefix.toLowerCase(Locale.ROOT));

		int start = IntegerArgumentType.getInteger(context, "start");
		int end = IntegerArgumentType.getInteger(context, "end");
		int from = Math.min(start, end);
		int to = Math.max(start, end);
		// Carpet Org 的约定：前缀没有下划线时自动补一个，batch bot 1 45 对应 bot_1 … bot_45
		String base = prefix.endsWith("_") ? prefix : prefix + "_";

		Set<String> wanted = new HashSet<>();
		if (!all) {
			for (int i = from; i <= to; i++) {
				wanted.add((prefix + i).toLowerCase(Locale.ROOT));
				wanted.add((base + i).toLowerCase(Locale.ROOT));
			}
		}

		List<ServerPlayer> bots = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!(player instanceof EntityPlayerMPFake)) {
				continue;
			}
			if (all) {
				bots.add(player);
				continue;
			}
			String name = player.getScoreboardName().toLowerCase(Locale.ROOT);
			if (wanted.contains(name) || hasWantedSuffix(name, wanted)) {
				bots.add(player);
			}
		}
		return bots;
	}

	/**
	 * 带 TIS / GCA 名字前缀时的匹配：目标名必须从分隔符之后开始。
	 *
	 * <p>例如 {@code batch bot 1 45} 能命中 {@code tis_bot_1}，但不会命中 {@code mybot_1}
	 * ——目标名前面必须是字母数字之外的字符。</p>
	 */
	private static boolean hasWantedSuffix(String name, Set<String> wanted) {
		for (int i = 1; i < name.length(); i++) {
			if (!Character.isLetterOrDigit(name.charAt(i - 1)) && wanted.contains(name.substring(i))) {
				return true;
			}
		}
		return false;
	}

	private static String describeTarget(CommandContext<CommandSourceStack> context) {
		return StringArgumentType.getString(context, "prefix")
				+ " " + IntegerArgumentType.getInteger(context, "start")
				+ ".." + IntegerArgumentType.getInteger(context, "end");
	}
}
