package carpetccu;

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
public final class CarpetCcuSettings {

	/**
	 * 本模组的规则分类名。自定义分类是允许的，Carpet 会自动收进设置管理器，
	 * {@code /carpet list CCU} 即可筛出本模组的规则。
	 */
	public static final String CCU = "CCU";

	/**
	 * 假人相关规则的子分类，{@code /carpet list CCU_fake_player} 可单独筛选。
	 */
	public static final String CCU_FAKE_PLAYER = "CCU_fake_player";

	private CarpetCcuSettings() {
	}

	@Rule(
			categories = {CCU, RuleCategory.CREATIVE, RuleCategory.FEATURE},
			options = {"false", "true"},
			strict = true
	)
	public static boolean ccuDebugLog = false;

	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.SURVIVAL, RuleCategory.FEATURE})
	public static boolean ccuFakePlayerProfilePreload = false;

	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.SURVIVAL, RuleCategory.FEATURE})
	public static String ccuFakePlayerNames = "";

	/**
	 * 控制 {@code /ccuplayer} 命令的可用级别，取值与 Carpet 的 {@code commandPlayer} 一致：
	 * false / true / ops / 0 / 1 / 2 / 3 / 4。
	 */
	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.COMMAND})
	public static String ccuCommandBatchPlayerAction = "ops";

	/**
	 * 控制 {@code /ccuload} 命令的可用级别，取值同 {@code commandPlayer}。
	 */
	@Rule(categories = {CCU, CCU_FAKE_PLAYER, RuleCategory.COMMAND})
	public static String ccuCommandPlayerLoadRange = "ops";
}
