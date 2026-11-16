package com.ccu.addition.feature;

import com.ccu.addition.CcuServer;
import com.ccu.addition.CcuRules;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 假人档案预加载。
 *
 * <p>背景：Carpet 的 {@code /player <名字> spawn} 在创建假人时会先确定这个玩家的 UUID
 * （{@code OldUsersConverter.convertMobOwnerIfNecessary}，内部读 {@code nameToIdCache}），
 * 再解析玩家档案拿皮肤。名字若从未在服务器出现过，这一步在正版验证开启时会直接失败，
 * 表现为“假人召不出来”。</p>
 *
 * <p>本功能在服务器启动时把配置里的假人名字预先登记进 {@code usercache.json}
 * （{@link UserNameToIdResolver}）：</p>
 * <ul>
 *     <li>名字已在缓存里（例如真的登录过）——原样保留，不动它的 UUID 与皮肤；</li>
 *     <li>名字不在缓存——按离线 UUID 规则生成并写入，UUID 稳定可复现，重启后不变。</li>
 * </ul>
 *
 * <p><strong>只看本地缓存，不联网</strong>：是否“已存在”是直接读 {@code usercache.json} 判断的，
 * 不会调用 {@code UserNameToIdResolver#get(String)}——那个方法在未命中时会向 Mojang 发起查询，
 * 在断网或网络受限的服务器上会让启动卡住。</p>
 *
 * <p>注意：规则只在服务器启动时生效，运行期改动 {@code fakePlayerNames} 需要重启服务器。</p>
 */
public final class FakePlayerProfilePreloader {

	/**
	 * Minecraft 用户名长度限制，用于提前过滤掉不可能存在的名字。
	 */
	private static final int MIN_NAME_LENGTH = 3;
	private static final int MAX_NAME_LENGTH = 16;

	private static final String USERCACHE_FILE = "usercache.json";

	private FakePlayerProfilePreloader() {
	}

	/**
	 * 在服务器、世界加载完成后执行一次预加载。
	 */
	public static void preload(MinecraftServer server) {
		if (!CcuRules.fakePlayerProfilePreload) {
			return;
		}

		List<String> names = parseNames(CcuRules.fakePlayerNames);
		if (names.isEmpty()) {
			CcuServer.LOGGER.warn("规则 fakePlayerProfilePreload 已开启，但 fakePlayerNames 为空，跳过预加载");
			return;
		}

		Set<String> known = readKnownNames(server);
		UserNameToIdResolver cache = server.services().nameToIdCache();

		int added = 0;
		int existed = 0;
		int invalid = 0;

		for (String name : names) {
			if (!isValidName(name)) {
				invalid++;
				CcuServer.LOGGER.warn("跳过非法假人名（长度 {}-{}，仅限字母数字下划线）：{}", MIN_NAME_LENGTH, MAX_NAME_LENGTH, name);
				continue;
			}

			if (known.contains(name.toLowerCase(Locale.ROOT))) {
				existed++;
				continue;
			}

			// 离线 UUID：UUID.nameUUIDFromBytes("OfflinePlayer:" + name)，与正版验证无关、可复现
			cache.add(NameAndId.createOffline(name));
			added++;
		}

		if (added > 0) {
			// 立刻落盘，避免服务器异常关闭时预加载结果丢失
			cache.save();
		}

		CcuServer.LOGGER.info(
				"假人档案预加载完成：新增 {}，已存在 {}，非法 {}（共 {} 个名字）",
				added, existed, invalid, names.size()
		);
	}

	/**
	 * 读取 {@code usercache.json} 里已知的玩家名（小写），仅本地文件，不触发任何网络查询。
	 */
	private static Set<String> readKnownNames(MinecraftServer server) {
		Path file = server.getFile(USERCACHE_FILE);
		if (!Files.isRegularFile(file)) {
			return Set.of();
		}
		try {
			String content = Files.readString(file, StandardCharsets.UTF_8);
			JsonArray array = JsonParser.parseString(content).getAsJsonArray();
			Set<String> names = new HashSet<>();
			for (JsonElement element : array) {
				JsonObject object = element.getAsJsonObject();
				if (object.has("name")) {
					names.add(object.get("name").getAsString().toLowerCase(Locale.ROOT));
				}
			}
			return names;
		} catch (Exception e) {
			CcuServer.LOGGER.warn("读取 {} 失败，将视为没有任何已缓存的假人档案", USERCACHE_FILE, e);
			return Set.of();
		}
	}

	/**
	 * 解析规则里的名字列表，支持逗号、分号与空白分隔，并按出现顺序去重（忽略大小写）。
	 */
	public static List<String> parseNames(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		String[] parts = raw.split("[,;\\s]+");
		Set<String> unique = new LinkedHashSet<>();
		Set<String> lowerCase = new HashSet<>();
		for (String part : parts) {
			String name = part.strip();
			if (!name.isEmpty() && lowerCase.add(name.toLowerCase(Locale.ROOT))) {
				unique.add(name);
			}
		}
		return new ArrayList<>(unique);
	}

	private static boolean isValidName(String name) {
		if (name.length() < MIN_NAME_LENGTH || name.length() > MAX_NAME_LENGTH) {
			return false;
		}
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9') || c == '_';
			if (!allowed) {
				return false;
			}
		}
		return true;
	}
}
