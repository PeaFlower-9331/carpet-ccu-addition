package com.ccu.addition.feature;

import com.ccu.addition.CcuRules;
import com.ccu.addition.CcuServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 「透明试炼方块」的活塞穿过后复原。
 *
 * <p>规则把宝库 / 试炼刷怪笼 / 紫水晶母岩变成幽灵方块后，活塞可以把它们「推掉」继续走；
 * 但方块本身不该就此消失，所以这里做登记 + 复原：</p>
 *
 * <ol>
 *     <li>活塞动画期间（{@link #beginMove()} ~ {@link #endMove(boolean)}），{@code Level#setBlock}
 *     把目标方块替换掉时，记下它的**完整 BlockState** 和方块实体 NBT；</li>
 *     <li>之后每刻检查：位置是空气、且周围 6 个方块连续 {@value #STABLE_TICKS} 刻没变化，就原样放回；</li>
 *     <li>最多等 {@value #FALLBACK_MS} 毫秒（15 分钟）：超时就写回配置文件、本局不再尝试，下次开服继续；</li>
 *     <li>只处理已加载的区块，未加载时挂起，避免为了复原强行加载区块。</li>
 * </ol>
 *
 * <p>与 Carpet IGNY Addition 的实现相比，这里保存的是完整 BlockState（{@link NbtUtils#writeBlockState}），
 * 复原时整体还原，所以不祥宝库不会退化成普通宝库，朝向和 vault 状态也不会丢。</p>
 */
public final class NightmarishBlockStore {

	/**
	 * 兜底时长（毫秒）：等这么久还没满足复原条件就落盘挂起，下次开服再试。
	 */
	private static final long FALLBACK_MS = 15 * 60_000L;

	/**
	 * 需要连续稳定多少刻才复原（活塞动画是 2 刻，留一点余量）。
	 */
	private static final int STABLE_TICKS = 2;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 活塞动画期间为 true，{@code Level#setBlock} 的拦截只在此时查方块。
	 */
	private static boolean moving = false;

	/**
	 * 待复原登记：{@code 维度|posLong} -> 记录。
	 */
	private static final Map<String, Pending> PENDING = new HashMap<>();

	private NightmarishBlockStore() {
	}

	/**
	 * 一条待复原记录。
	 */
	private static final class Pending {
		private final BlockState state;
		private final CompoundTag blockEntity;
		private long deadlineMs;
		private int stableTicks;
		private BlockState[] neighbors;
		private boolean deferred;

		private Pending(BlockState state, CompoundTag blockEntity, long deadlineMs) {
			this.state = state;
			this.blockEntity = blockEntity;
			this.deadlineMs = deadlineMs;
		}
	}

	// ---------------------------------------------------------------- 规则判定

	/**
	 * @return 这个方块状态是否在当前规则选项的命中范围内
	 */
	public static boolean matches(BlockState state) {
		return state != null && matchesBlock(state.getBlock());
	}

	/**
	 * 方块版本的重载：{@code BlockStateBase} 里拿不到 BlockState 时用它。
	 */
	public static boolean matchesBlock(Block block) {
		CcuRules.NightmarishMode mode = CcuRules.betaTransparentNightmarishBlock;
		if (mode == CcuRules.NightmarishMode.FALSE || block == null) {
			return false;
		}
		if (block == Blocks.VAULT || block == Blocks.TRIAL_SPAWNER) {
			return mode.trial();
		}
		if (block == Blocks.BUDDING_AMETHYST) {
			return mode.amethyst();
		}
		return false;
	}

	// ---------------------------------------------------------------- 活塞动画标记

	public static void beginMove() {
		moving = true;
	}

	public static void endMove(boolean previous) {
		moving = previous;
	}

	public static boolean isMoving() {
		return moving;
	}

	// ---------------------------------------------------------------- 登记与复原

	/**
	 * {@code Level#setBlock} 头部调用：活塞把命中方块替换掉时登记它。
	 */
	public static void capture(Level level, BlockPos pos, BlockState newState) {
		if (!moving || level.isClientSide()) {
			return;
		}
		BlockState oldState = level.getBlockState(pos);
		if (!matches(oldState) || matches(newState)) {
			return;
		}
		CompoundTag blockEntity = null;
		BlockEntity be = level.getBlockEntity(pos);
		if (be != null) {
			try {
				blockEntity = be.saveWithFullMetadata(level.registryAccess());
			} catch (Throwable e) {
				CcuServer.LOGGER.warn("透明试炼方块：保存 {} @ {} 的方块实体失败", oldState, pos, e);
			}
		}
		PENDING.put(key(level, pos), new Pending(oldState, blockEntity, System.currentTimeMillis() + FALLBACK_MS));
		CcuServer.debug("透明试炼方块：%s @ %s 被活塞移走，已登记复原", oldState, pos);
	}

	/**
	 * 每刻推进一次复原：空气 + 周围稳定就放回，超时就落盘挂起。
	 */
	public static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean dirty = false;

		Iterator<Map.Entry<String, Pending>> iterator = PENDING.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<String, Pending> entry = iterator.next();
			Pending pending = entry.getValue();
			if (pending.deferred) {
				continue;
			}
			String dimensionId = entry.getKey().substring(0, entry.getKey().indexOf('|'));
			long posLong = Long.parseLong(entry.getKey().substring(entry.getKey().indexOf('|') + 1));
			ServerLevel level = findLevel(server, dimensionId);
			BlockPos pos = BlockPos.of(posLong);

			if (level == null || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
				// 维度或区块还没加载，先挂着
				continue;
			}

			BlockState current = level.getBlockState(pos);
			if (!current.isAir()) {
				pending.neighbors = null;
				pending.stableTicks = 0;
				continue;
			}

			BlockState[] neighbors = neighbors(level, pos);
			if (pending.neighbors != null && sameStates(pending.neighbors, neighbors)) {
				pending.stableTicks++;
			} else {
				pending.stableTicks = 0;
			}
			pending.neighbors = neighbors;

			if (pending.stableTicks >= STABLE_TICKS) {
				restore(level, pos, pending);
				iterator.remove();
				dirty = true;
				continue;
			}

			if (now > pending.deadlineMs) {
				pending.deferred = true;
				CcuServer.debug("透明试炼方块：%s @ %s 等了 %d 分钟仍不满足复原条件，本局先挂起",
						pending.state, pos, FALLBACK_MS / 60_000L);
			}
		}

		if (dirty) {
			save();
		}
	}

	private static void restore(ServerLevel level, BlockPos pos, Pending pending) {
		level.setBlock(pos, pending.state, 2 | 16);
		if (pending.blockEntity != null) {
			BlockEntity be = level.getBlockEntity(pos);
			if (be != null) {
				try {
					be.loadWithComponents(TagValueInput.create(
							ProblemReporter.DISCARDING, level.registryAccess(), pending.blockEntity));
					be.setChanged();
				} catch (Throwable e) {
					CcuServer.LOGGER.warn("透明试炼方块：复原 {} @ {} 的方块实体失败", pending.state, pos, e);
				}
			}
		}
		CcuServer.debug("透明试炼方块：已复原 %s @ %s", pending.state, pos);
	}

	private static BlockState[] neighbors(Level level, BlockPos pos) {
		BlockState[] states = new BlockState[6];
		for (int i = 0; i < 6; i++) {
			states[i] = level.getBlockState(pos.relative(DIRECTIONS[i]));
		}
		return states;
	}

	private static final net.minecraft.core.Direction[] DIRECTIONS = net.minecraft.core.Direction.values();

	private static boolean sameStates(BlockState[] a, BlockState[] b) {
		for (int i = 0; i < a.length; i++) {
			if (a[i] != b[i]) {
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- 持久化

	private static Path storePath() {
		return FabricLoader.getInstance().getConfigDir()
				.resolve("carpet-ccu-addition").resolve("nightmarish-blocks.json");
	}

	private static String key(Level level, BlockPos pos) {
		return DimensionIds.of(level.dimension()) + "|" + pos.asLong();
	}

	private static ServerLevel findLevel(MinecraftServer server, String dimensionId) {
		for (ServerLevel level : server.getAllLevels()) {
			if (DimensionIds.of(level.dimension()).equals(dimensionId)) {
				return level;
			}
		}
		return null;
	}

	/**
	 * 读取上次没复原完的登记，全部重新纳入本轮（重新计时）。
	 */
	public static void load() {
		PENDING.clear();
		Path path = storePath();
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			JsonObject json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonObject entries = json.getAsJsonObject("entries");
			if (entries == null) {
				return;
			}
			long now = System.currentTimeMillis();
			for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
				try {
					JsonObject value = entry.getValue().getAsJsonObject();
					CompoundTag stateTag = TagParser.parseCompoundFully(value.get("state").getAsString());
					BlockState state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, stateTag);
					if (state.isAir()) {
						continue;
					}
					CompoundTag beTag = value.has("nbt") && !value.get("nbt").getAsString().isEmpty()
							? TagParser.parseCompoundFully(value.get("nbt").getAsString())
							: null;
					PENDING.put(entry.getKey(), new Pending(state, beTag, now + FALLBACK_MS));
					CcuServer.debug("透明试炼方块：读回待复原 {} @ {}", state, entry.getKey());
				} catch (Exception e) {
					CcuServer.LOGGER.warn("透明试炼方块：跳过无法解析的登记项 {}：{}", entry.getKey(), e.toString());
				}
			}
		} catch (Exception e) {
			CcuServer.LOGGER.warn("透明试炼方块：读取复原登记失败：{}", path, e);
		}
	}

	/**
	 * 落盘（关服或复原成功后调用）。
	 */
	public static void save() {
		Path path = storePath();
		try {
			Files.createDirectories(path.getParent());
			JsonObject entries = new JsonObject();
			PENDING.forEach((key, pending) -> {
				JsonObject value = new JsonObject();
				value.addProperty("state", NbtUtils.writeBlockState(pending.state).toString());
				value.addProperty("nbt", pending.blockEntity == null ? "" : pending.blockEntity.toString());
				entries.add(key, value);
			});
			JsonObject json = new JsonObject();
			json.add("entries", entries);
			Files.writeString(path, GSON.toJson(json), StandardCharsets.UTF_8);
		} catch (Exception e) {
			CcuServer.LOGGER.warn("透明试炼方块：保存复原登记失败：{}", path, e);
		}
	}

	/**
	 * 供命令/诊断使用。
	 */
	public static int size() {
		return PENDING.size();
	}

}
