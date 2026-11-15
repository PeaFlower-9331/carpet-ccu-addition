# 规则清单

命令：`/carpet <规则名> <值>`；按分类筛选：`/carpet list CCU`、`/carpet list CCU_fake_player`。

| 规则 | 类型 | 默认值 | 分类 | 说明 |
| --- | --- | --- | --- | --- |
| `ccuDebugLog` | boolean | `false` | CCU / creative / feature | 输出本模组的详细生命周期日志，仅影响日志详细程度 |
| `ccuFakePlayerProfilePreload` | boolean | `false` | CCU / CCU_fake_player / survival / feature | 服务器启动时预加载假人档案 |
| `ccuFakePlayerNames` | string | `""` | CCU / CCU_fake_player / survival / feature | 要预加载的假人名字，逗号或空格分隔 |
| `ccuCommandBatchPlayerAction` | string | `"ops"` | CCU / CCU_fake_player / command | `/ccuplayer` 与 `batch action` 的可用级别，取值同 `commandPlayer` |

## 批量假人动作

### 命令

装了 **Carpet Org Addition** 时，命令并入它的批量命令：

```
/playerManager batch action <目标> <动作>
```

没装时（或想用短命令）用等价入口：

```
/ccuplayer <目标> <动作>
```

`<目标>` 的匹配规则：

| 写法 | 含义 |
| --- | --- |
| `all` 或 `*` | 服务器上所有假人 |
| 名字前缀 | 不区分大小写，`bot` 命中 `bot_1`、`bot_10`、`botFarm` |

只作用于假人（`EntityPlayerMPFake`），**不会误控真人玩家**。

### 动作

动作不自行解析，直接交给 Carpet 的 `/player` 执行，所以 **`/player` 支持什么，这里就支持什么**：

```bash
# 批量连续右键
/playerManager batch action bot use continuous

# 批量跳一次 / 连续跳
/playerManager batch action bot jump
/playerManager batch action bot jump continuous

# 批量每 5 刻攻击一次
/playerManager batch action bot attack interval 5

# 批量潜行 / 取消潜行
/playerManager batch action bot sneak
/playerManager batch action bot unsneak

# 批量前进 / 停下
/playerManager batch action bot move forward
/playerManager batch action all stop

# 批量看向某个方向
/playerManager batch action bot look north
```

每条动作的回显被抑制，批量操作不会刷屏，结束时只回一行汇总：

```
已对 10 个假人执行：/player <名字> use continuous
```

### 实现说明

- Carpet Org 的批量命令节点形状是 `batch <prefix> <start> <end> spawn|kill|drop|trial`，
  而它的命令名允许玩家在配置里改，所以本模组**不硬编码命令名**：服务器启动后按结构找到该节点，
  在 `batch` 这一层挂一个 `action` 字面量。Brigadier 解析时字面量优先于参数，
  因此 `batch action ...` 不会被当成 `prefix` 吞掉。
- 挂载在 `onServerLoaded` 做一次，未命中时在前几个 tick 重试；`/reload` 重建命令树后会重新挂载。
- 内部通过 `dispatcher.execute("player <名字> " + 动作, source.withSuppressedOutput())` 执行，
  既复用了 Carpet 的全部语法与校验，也自动跟随 Carpet 后续新增的动作。
- 若服务器关闭了 Carpet 的 `commandPlayer` 规则，本命令会报“未知命令”，错误信息里会给出提示。

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
