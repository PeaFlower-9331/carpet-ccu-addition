# 规则清单

命令：`/carpet <规则名> <值>`；按分类筛选：`/carpet list CCU`、`/carpet list CCU_fake_player`。

| 规则 | 类型 | 默认值 | 分类 | 说明 |
| --- | --- | --- | --- | --- |
| `debugLog` | boolean | `false` | CCU / creative / feature | 输出本模组的详细生命周期日志，仅影响日志详细程度 |
| `fakePlayerProfilePreload` | boolean | `false` | CCU / CCU_fake_player / survival / feature | 服务器启动时预加载假人档案 |
| `fakePlayerNames` | string | `""` | CCU / CCU_fake_player / survival / feature | 要预加载的假人名字，逗号或空格分隔 |
| `commandBatchPlayerAction` | string | `"ops"` | CCU / CCU_fake_player / command | `batch action` 的可用级别，取值同 `commandPlayer` |
| `commandPlayerLoadRange` | string | `"ops"` | CCU / CCU_fake_player / command | `/player <名字> load` 的可用级别，取值同 `commandPlayer` |

## 假人加载范围

> 分类：`CCU` / `假人`（`CCU_fake_player`）。**只能调大，不能缩小。**

### 命令

命令已并入 Carpet 的 `/player`，没有额外的独立命令：

```
/player bot1 load              # 查看 bot1 当前的设置
/player bot1 load 8            # 把 bot1 的加载范围调到 8 区块（实体刻）
/player bot1 load default      # 恢复默认：跟随服务器视距
```

- 未设置的假人**不额外加载**，行为与原版完全一致（只按服务器 `view-distance` 加载）。
- 设置按假人**名字**保存，写在 `config/carpet-ccu-addition/load-range.json`，假人重新召唤、服务器重启后都继续生效。
- 半径范围 `1`–`32`；`default` 或 `-1` 表示清除。
- **只能调大**：填的值不大于服务器视距时不会产生额外效果，命令会直接提示。

### 为什么只能调大

原版对玩家（包括假人）的区块加载半径是由服务器视距 / 模拟距离**统一**决定的，没有按玩家单独配置的入口；
而区块 ticket 只能叠加、不能抵消。所以「给某个假人缩小」做不到——原版那部分 ticket 压不下去。

本功能因此只能做加法：给指定假人**额外挂一个区块 ticket**，把它周围更多区块强制保持加载。

```
TicketType(CCU, 不持久化, 加载 + 模拟)
  → ServerChunkCache#addTicketWithRadius(type, 假人所在区块, 半径 + 2)
```

换算关系：`TicketStorage#addTicketWithRadius(type, pos, r)` 创建的 ticket 等级是 `33 - r`，
而 ticket 等级每远离一个区块 +1，实体刻要求等级 ≤ 31；因此想要「半径 R 内实体刻」需要传 `R + 2`。

假人移动超过一个区块或半径变化时会撤销旧 ticket、挂新 ticket；假人下线立即撤销。

### 注意

| 事项 | 说明 |
| --- | --- |
| 只能调大，不能缩小 | 原版对玩家本身就有视距范围内的 ticket，设置值 ≤ 服务器视距时效果等同未设置 |
| ticket 不持久化 | 类型不带 `FLAG_PERSIST`，重启后不会在存档里留下残留 |
| 开销 | 半径每 +1，ticket 覆盖的区块按平方增长，32 区块 ≈ 4000+ 区块，注意服务器性能 |
| 生效延迟 | 新区块需要生成，半径较大时第一次生效可能要几秒到十几秒 |

## 矿车加载器

分类：`CCU` / `矿车`（`CCU_minecart`）。`/carpet list CCU_minecart` 可单独筛选。

指定种类的矿车停在指定种类的铁轨上时，**每 5 秒**给周围区块续一次加载票，保持加载与实体刻。

### 规则

| 规则 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `minecartChunkLoader` | boolean | `false` | 总开关 |
| `minecartLoaderTypes` | string | `"minecart"` | 触发加载的矿车种类，逗号分隔，可省略 `minecraft:` 前缀 |
| `minecartLoaderRails` | string | `"rail"` | 触发加载的铁轨种类，逗号分隔，同上 |
| `minecartLoaderRange` | int | `2` | 半径（区块），取值 1–32 |

### 示例

写进 `world/carpet.conf`：

```conf
minecartChunkLoader true
minecartLoaderTypes minecart,chest_minecart
minecartLoaderRails rail,powered_rail
minecartLoaderRange 8
```

也可以在游戏里逐条设：

```
/carpet minecartLoaderTypes minecart,chest_minecart
/carpet minecartLoaderRails rail,powered_rail
/carpet minecartLoaderRange 8
/carpet minecartChunkLoader true
```

### 行为

- 每 5 秒（100 刻）扫描一次**已加载**实体：矿车类型命中、且脚下或所在位置是配置的某种铁轨，就给它所在区块挂一次加载票。
- 加载票自带 **5 秒超时**（`TicketType` timeout=100 刻，且不持久化）：重复挂票会刷新超时；矿车开走、离开铁轨或被清除后，**最多 5 秒**自动卸载，不会在存档里留下残留。
- 只扫描已加载区块中的矿车，不会为了找车而强行加载区块；也不会影响矿车原本的行为。
- 矿车移动后旧区块的票不会立刻撤销，最多多残留 5 秒。
- 半径换算：`addTicketWithRadius(type, pos, R + 2)`，保证半径 R 内的区块处于实体刻状态。

## 批量假人动作

### 命令

命令并入 **Carpet Org Addition** 的批量命令（没有独立命令）：

```
/playerManager batch action <目标> <动作>
```

没有安装 Carpet Org Addition 时，本功能不会注册任何命令。

`<目标>` 的匹配规则：

| 写法 | 含义 |
| --- | --- |
| `all` 或 `*` | 服务器上所有假人 |
| 名字前缀 | 不区分大小写，`bot` 命中 `bot_1`、`bot_10`、`botFarm` |

只作用于假人（`EntityPlayerMPFake`），**不会误控真人玩家**。

### 动作

**列表直接由 Carpet 的 `/player` 命令树实时生成**（并按动作自身的 `requires` 过滤），所以和 `/player` 完全一致，Carpet 以后增删动作这里自动跟随。

查看当前可用动作：

```
/playerManager batch action      # 不带参数即列出动作
```

在默认配置（Carpet 1.4.193 + Carpet Org 1.41.7）下共 20 个：

| 动作 | 参数 | 说明 |
| --- | --- | --- |
| `stop` | — | 停止该假人的全部动作 |
| `use` | `once`（默认）/ `continuous` / `interval <刻>` | 右键/使用，连续右键用 `use continuous` |
| `jump` | 同上 | 跳跃 |
| `attack` | 同上 | 左键攻击 |
| `drop` | `once/continuous/interval` 或 `all/mainhand/offhand/<槽位>` | 丢弃物品 |
| `dropStack` | 同上 | 整摞丢弃 |
| `swapHands` | `once/continuous/interval` | 主副手交换 |
| `hotbar` | `<槽位>` | 切换快捷栏槽位 |
| `kill` | — | 杀死假人（使其下线） |
| `shadow` | — | 把真人转成影子假人（对假人无效） |
| `mount` | `[anything]` | 骑乘附近实体 |
| `dismount` | — | 下坐骑 |
| `sneak` / `unsneak` | — | 潜行 开/关 |
| `sprint` / `unsprint` | — | 疾跑 开/关 |
| `look` | `north/south/east/west/up/down/at <x y z>/<方向>` | 转向 |
| `turn` | `left/right/back/<yaw> <pitch>` | 相对旋转 |
| `move` | `forward/backward/left/right` | 持续移动（用 `stop` 或 `move` 停止） |
| `spawn` | `[in <模式>] [at <坐标> ...]` | 召唤新假人；批量场景通常无意义（目标已在线） |

示例：

```bash
# 批量连续右键
/playerManager batch action bot use continuous

# 批量跳一次 / 连续跳
/playerManager batch action bot jump
/playerManager batch action bot jump continuous

# 批量每 5 刻攻击一次
/playerManager batch action bot attack interval 5

# 批量前进 / 停下
/playerManager batch action bot move forward
/playerManager batch action all stop
```

每条动作的回显被抑制，批量操作不会刷屏，结束时只回一行汇总：

```
已对 10 个假人执行：/player <名字> use continuous
```

### 不参与批量的动作

| 动作 | 原因 |
| --- | --- |
| `inventory` / `enderChest` | 「查看假人背包/末影箱」是打开界面的动作，一次开一堆没有意义；需要时对单个假人执行 `/player <名字> inventory` |
| 被规则关闭的动作（如 `teleport`、`mannequin`） | 由 Carpet Org 自己的 `playerCommand` 系列开关控制，关着时 `/player` 本身也解析不到，因此不会出现在列表里 |

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
/carpet fakePlayerNames bot1,bot2,bot_farm

# 2. 打开开关
/carpet fakePlayerProfilePreload true

# 3. 重启服务器（规则只在启动时生效）
```

重启后日志会给出结果：

```
[CarpetCCU] 假人档案预加载完成：新增 3，已存在 0，非法 0（共 3 个名字）
```

之后 `/player bot1 spawn` 即可直接召唤。

也可以直接写进 `world/carpet.conf`：

```conf
fakePlayerProfilePreload true
fakePlayerNames bot1,bot2,bot_farm
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

1. `CcuRules` 加带 `@Rule` 的 `public static` 字段；
2. `assets/carpet_ccu/lang/{en_us,zh_cn}.json` 补 `carpet.rule.<字段名>.name` / `.desc`；
3. 在功能代码里读字段，并同步更新本文件。
