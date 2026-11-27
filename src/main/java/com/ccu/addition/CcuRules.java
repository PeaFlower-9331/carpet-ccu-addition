package com.ccu.addition;

import carpet.api.settings.Rule;
import carpet.api.settings.RuleCategory;

/**
 * 全部规则集中在这里。
 *
 * <p>加一条规则的步骤：</p>
 * <ol>
 *     <li>在下面加一个 {@code public static} 字段，带上 {@link Rule} 注解；</li>
 *     <li>在 {@code assets/carpet_ccu/lang/en_us.json} 与 {@code zh_cn.json} 里补
 *     {@code carpet.rule.<字段名>.name} 与 {@code .desc}；</li>
 *     <li>在功能代码里直接读这个字段。</li>
 * </ol>
 *
 * <p>字段必须是 public static 且非 final；可选类型与 Carpet 原生规则一致
 * （boolean / int / double / String / 枚举 等）。</p>
 *
 * <p>因为规则挂在主 {@code /carpet} 命名空间下，字段名建议保留 {@code ccu} 前缀，
 * 避免与其它附属的规则重名（重名会让 Carpet 在启动时直接报错）。</p>
 */
public final class CcuRules {

	/**
	 * 本模组的规则分类名。自定义分类是允许的，Carpet 会自动收进设置管理器，
	 * {@code /carpet list CCU} 即可筛出本模组的规则。
	 */
	public static final String CCU = "CCU";

	/**
	 * 假人相关规则的子分类，{@code /carpet list CCU_fake_player} 可单独筛选。
	 */
	public static final String CCU_FAKE_PLAYER = "CCU_fake_player";

	/**
	 * 矿车加载器规则的子分类，{@code /carpet list CCU_minecart} 可单独筛选。
	 */
	public static final String CCU_MINECART = "CCU_minecart";

	private CcuRules() {
	}

	@Rule(
			categories = {CCU, RuleCategory.CREATIVE, RuleCategory.FEATURE},
			options = {"false", "true"},
			strict = true
	)
	public static boolean debugLog = false;

	/**
	 * 用 {@code /player <名字> spawn} 召唤假人时，先把档案（UUID + 皮肤）异步预加载好，再真正召唤，
	 * 避免召唤瞬间因查询档案而卡顿。
	 */
	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.SURVIVAL, RuleCategory.FEATURE})
	public static boolean fakePlayerSpawnPreload = true;

	/**
	 * 假人重新上线时回到原来乘坐的载具。
	 *
	 * <p>覆盖两条路径：Carpet TIS Addition 的 {@code /player <名字> rejoin}，以及
	 * GugleCarpetAddition 的 {@code fakePlayerResident}（关服时记住假人、开服自动拉起来）。
	 * 两者都不会恢复载具——原版只在真正的登录流程（{@code PrepareSpawnTask}）里读 playerdata 的
	 * {@code RootVehicle} 并调用 {@code loadAndSpawnParentVehicle}，假人绕过了这一步。</p>
	 *
	 * <p>实现见 {@code FakePlayerRideRestore}：登录时读 playerdata 里记录的载具 UUID，
	 * 等载具区块加载出来后重新上座；只上座、不生成实体，并且要求载具离假人 8 格以内。</p>
	 */
	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.SURVIVAL, RuleCategory.FEATURE})
	public static boolean fakePlayerRejoinKeepRiding = true;

	/**
	 * 控制批量假人动作命令的可用级别，取值与 Carpet 的 {@code commandPlayer} 一致：
	 * false / true / ops / 0 / 1 / 2 / 3 / 4。
	 */
	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.COMMAND})
	public static String commandBatchPlayerAction = "ops";

	/**
	 * 控制 {@code /player <名字> load} 命令的可用级别，取值同 {@code commandPlayer}。
	 */
	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.COMMAND})
	public static String commandPlayerLoadRange = "ops";

	// ---------------------------------------------------------------- 矿车加载器

	@Rule(categories = {CCU, CCU_MINECART, RuleCategory.FEATURE, RuleCategory.CREATIVE})
	public static boolean minecartChunkLoader = false;

	@Rule(categories = {CCU, CCU_MINECART, RuleCategory.FEATURE, RuleCategory.CREATIVE})
	public static CartPreset minecartLoaderTypes = CartPreset.MINECART;

	@Rule(categories = {CCU, CCU_MINECART, RuleCategory.FEATURE, RuleCategory.CREATIVE})
	public static RailPreset minecartLoaderRails = RailPreset.DETECTOR_RAIL;

	@Rule(categories = {CCU, CCU_MINECART, RuleCategory.FEATURE, RuleCategory.CREATIVE})
	public static int minecartLoaderRange = 2;

	/**
	 * 扫描间隔（刻）。判定铁轨、续票、发现新矿车都在这一轮里做，默认 100 刻（5 秒）。
	 *
	 * <p>调小可以让判定更及时（矿车整个推进周期里可能只有 2 刻停在目标铁轨上），
	 * 代价是每轮都要遍历一次实体、并按半径给命中矿车挂票；调大则相反。</p>
	 */
	@Rule(categories = {CCU, CCU_MINECART, RuleCategory.FEATURE, RuleCategory.CREATIVE})
	public static int minecartScanInterval = 100;

	// ---------------------------------------------------------------- 预设

	/**
	 * 铁轨预设：只列原版四种铁轨，省得记 id。
	 *
	 * <p>枚举常量名必须与注册表 id 一致（小写后 = {@code rail}、{@code powered_rail} …），
	 * Carpet 会把枚举常量小写后当作可选值，并在 {@code /carpet} 里直接列出来。</p>
	 */
	public enum RailPreset {
		RAIL,
		POWERED_RAIL,
		DETECTOR_RAIL,
		ACTIVATOR_RAIL;

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/**
	 * 矿车预设：原版七种矿车，默认普通矿车。
	 */
	public enum CartPreset {
		MINECART,
		CHEST_MINECART,
		HOPPER_MINECART,
		TNT_MINECART,
		SPAWNER_MINECART,
		COMMAND_BLOCK_MINECART,
		FURNACE_MINECART;

		public String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}
}
