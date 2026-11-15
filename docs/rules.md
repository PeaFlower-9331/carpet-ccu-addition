# 规则清单

命令：`/carpet <规则名> <值>`；按分类筛选：`/carpet list CCU`、`/carpet list CCU_fake_player`。

| 规则 | 类型 | 默认值 | 分类 | 说明 |
| --- | --- | --- | --- | --- |
| `ccuDebugLog` | boolean | `false` | CCU / creative / feature | 输出本模组的详细生命周期日志，仅影响日志详细程度 |
| `ccuFakePlayerProfilePreload` | boolean | `false` | CCU / CCU_fake_player / survival / feature | 服务器启动时预加载假人档案 |
| `ccuFakePlayerNames` | string | `""` | CCU / CCU_fake_player / survival / feature | 要预加载的假人名字，逗号或空格分隔 |

## 假人档案预加载

### 它解决什么问题

Carpet 的 `/player <名字> spawn` 创建假人时，先用名字确定 UUID
（`OldUsersConverter.convertMobOwnerIfNecessary`，内部读 `usercache`），再解析玩家档案拿皮肤。

- 名字在 `usercache.json` 里没有记录时，**正版验证开启**（`online-mode=true`）的服务器拿不到 UUID，假人直接召不出来；
- 即使召出来，也要现场向 Mojang 查一次档案，网络不好时会卡住甚至失败。

预加载就是把假人的「名字 → UUID」提前写进 `usercache.json`，让之后召唤时直接命中。

### 怎么用

```bash
# 1. 写名字列表（逗号或空格分隔）
/carpet ccuFakePlayerNames bot1,bot2,bot_farm

# 2. 打开开关
/carpet ccuFakePlayerProfilePreload true

# 3. 重启服务器（规则只在启动时生效）
```

重启后日志会给出结果：

```
[CarpetCCU] 假人档案预加载完成：新增 3，已存在 0，非法 0（共 3 个名字）
```

之后 `/player bot1 spawn` 即可直接召唤。

也可以直接写进 `world/carpet.conf`：

```conf
ccuFakePlayerProfilePreload true
ccuFakePlayerNames bot1,bot2,bot_farm
```

### 行为细节

| 情况 | 处理 |
| --- | --- |
| 名字已在 `usercache.json` | 原样保留，不改它的 UUID 与皮肤 |
| 名字不在缓存 | 按离线 UUID 生成（`OfflinePlayer:<名字>` 的 MD5 v3），写入并立即落盘 |
| 名字非法（非 3-16 位，或含字母数字下划线以外的字符） | 跳过，打一条警告 |
| 列表里大小写重复（`Bot1` / `bot1`） | 只保留第一次出现的形式 |

- **只读本地文件，不联网**：判断“是否已存在”是直接读 `usercache.json`，不会调用
  `UserNameToIdResolver#get(String)`——那个方法未命中时会向 Mojang 发起查询，断网或网络受限的服务器会卡在启动阶段。
- **UUID 可复现**：离线 UUID 只由名字决定，换机器、重启都不会变，适合挂机/商店/权限等按 UUID 记录的场景。
- 预加载写入的是**离线 UUID**。如果该名字是真实正版账号且你希望用正版 UUID，请让该账号先登录一次
  （或直接用 `/player` 让它自己解析），预加载会识别到已有记录并跳过。
- 运行期修改规则或名字列表不会立即生效，需要重启服务器。

## 加规则的三步

1. `CarpetCcuSettings` 加带 `@Rule` 的 `public static` 字段；
2. `assets/carpet_ccu/lang/{en_us,zh_cn}.json` 补 `carpet.rule.<字段名>.name` / `.desc`；
3. 在功能代码里读字段，并同步更新本文件。
