package carpetccu.command;

import carpet.patches.EntityPlayerMPFake;
import carpet.utils.CommandHelper;
import carpetccu.CarpetCcuSettings;
import carpetccu.feature.FakePlayerChunkLoader;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /ccuload <假人名> <半径|default>}：给某个假人单独设定加载范围。
 *
 * <pre>
 * /ccuload                 // 列出已有的设置
 * /ccuload bot1 8          // bot1 周围 8 个区块保持加载并实体刻
 * /ccuload bot1 default    // 恢复默认（跟随服务器视距）
 * </pre>
 *
 * <p>设置按名字保存，假人重新召唤后依然生效；未设置的假人不额外加载，行为与原版一致。</p>
 */
public final class LoadRangeCommand {

	private LoadRangeCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("ccuload")
				.requires(source -> CommandHelper.canUseCommand(source, CarpetCcuSettings.ccuCommandPlayerLoadRange))
				.executes(LoadRangeCommand::list)
				.then(Commands.argument("target", StringArgumentType.word())
						.suggests((context, builder) -> SharedSuggestionProvider.suggest(
								names(context.getSource().getServer()), builder))
						.then(Commands.argument("radius", StringArgumentType.word())
								.suggests((context, builder) -> SharedSuggestionProvider.suggest(
										List.of("default", "0", "4", "8", "16", "24", "32"), builder))
								.executes(LoadRangeCommand::set))));
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		Map<String, Integer> ranges = FakePlayerChunkLoader.allRanges();
		if (ranges.isEmpty()) {
			source.sendSuccess(() -> Component.literal("当前没有任何假人设置额外加载范围（都跟随服务器视距）"), false);
			return 0;
		}
		source.sendSuccess(() -> Component.literal("假人额外加载范围（区块）："), false);
		ranges.forEach((name, radius) ->
				source.sendSuccess(() -> Component.literal(" - " + name + "：" + radius), false));
		return ranges.size();
	}

	private static int set(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String target = StringArgumentType.getString(context, "target");
		String raw = StringArgumentType.getString(context, "radius").strip();

		int radius;
		if (raw.equalsIgnoreCase("default") || raw.equals("0") || raw.equals("-1")) {
			radius = 0;
		} else {
			try {
				radius = Integer.parseInt(raw);
			} catch (NumberFormatException e) {
				source.sendFailure(Component.literal("半径必须是整数或 default：" + raw));
				return 0;
			}
			if (radius < 0 || radius > FakePlayerChunkLoader.MAX_RADIUS) {
				source.sendFailure(Component.literal("半径需在 0-" + FakePlayerChunkLoader.MAX_RADIUS + " 之间：" + radius));
				return 0;
			}
		}

		int previous = FakePlayerChunkLoader.setRange(target, radius);

		boolean online = source.getServer().getPlayerList().getPlayerByName(target) instanceof EntityPlayerMPFake;
		if (radius <= 0) {
			source.sendSuccess(() -> Component.literal("已恢复 " + target + " 的默认加载范围（跟随服务器视距，之前是 "
					+ previous + " 区块）"), true);
		} else {
			source.sendSuccess(() -> Component.literal("已设置 " + target + " 的额外加载范围为 " + radius + " 区块"
					+ (online ? "（已生效）" : "（该假人未在线，召唤后自动生效）")), true);
		}
		// 立即对账一次，不必等下一个检查周期
		FakePlayerChunkLoader.tick(source.getServer());
		return 1;
	}

	private static List<String> names(MinecraftServer server) {
		List<String> names = new ArrayList<>();
		names.addAll(FakePlayerChunkLoader.allRanges().keySet());
		server.getPlayerList().getPlayers().stream()
				.filter(player -> player instanceof EntityPlayerMPFake)
				.map(player -> player.getScoreboardName())
				.sorted()
				.forEach(names::add);
		return names;
	}
}
