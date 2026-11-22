# 规则清单

命令：`/carpet <规则名> <值>`；按分类筛选：`/carpet list CCU`、`/carpet list CCU_fake_player`。

| 规则 | 类型 | 默认值 | 分类 | 说明 |
| --- | --- | --- | --- | --- |
| `debugLog` | boolean | `false` | CCU / creative / feature | 输出本模组的详细生命周期日志，仅影响日志详细程度 |
| `fakePlayerSpawnPreload` | boolean | `true` | CCU / CCU_fake_player / survival / feature | 召唤假人时先异步预加载档案再召唤，避免卡顿 |
| `fakePlayerRejoinKeepRiding` | boolean | `true` | CCU / CCU_fake_player / survival / feature | 配合 TIS 的 `/player <名字> rejoin`，保留原来乘坐的载具 |
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
- 加载票自带 **15 秒超时**（`TicketType` timeout=300 刻，且不持久化）：每 5 秒重复挂票会刷新超时；矿车开走、离开铁轨或被清除后，**最多 15 秒**自动卸载，不会在存档里留下残留。
- 超时故意比续票间隔长，留两轮容错：矿车跨方块边界、上坡、被活塞推离轨道，或铁轨刚被拆掉的那一瞬间都可能判不到「在铁轨上」，连续两轮落空之前不会断票。
- 只扫描已加载区块中的矿车，不会为了找车而强行加载区块；也不会影响矿车原本的行为。
- 矿车移动后旧区块的票不会立刻撤销，最多多残留 15 秒。
- **重启自举**：每辆成功续票的矿车会记进 `config/carpet-ccu-addition/minecart-anchors.json`。服务器重启（或锚点区块被卸载）后，加载器会按记录先把区块加载起来，把矿车找回来再照常续票；找不回来的锚点会自动清掉，不会留下永久加载的区块。
- 铁轨判定另有 **5 秒宽限**：矿车最近一次确认在铁轨上之后 5 秒内，即使当轮判定不成立也继续续票，移动中的矿车靠这条撑过短暂空档。
- **被活塞带动**：活塞动画期间，铁轨所在的方块会临时变成 `moving_piston`，此时判定不到铁轨。对已经被认定的加载器矿车（有锚点记录），这种情况按「机器在走」处理，继续续票。世界吞噬者这类整机被推着走的场景必须依赖这条。
- **锚点保活**：最近 2 分钟（真实时间）内确认过的矿车，按锚点位置持续挂票。即使某几轮判定不到铁轨、甚至区块已经被卸载，票也不会断，矿车实体会被重新加载回来。
- **锚点过期**：连续 30 秒没有被识别（既不在指定铁轨上、也没被活塞带动）的锚点会被丢弃并写回文件，重启后不再对它做自举加载。删除发生在运行期间，所以不会「每次重启都白加载一次」；只有删掉它的那一次重启会多加载约 30 秒。
- 保活与过期都按**真实时间**计算，不受 tick rate 影响（按刻算的话，100 tps 下会被压缩 5 倍）。
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

## 假人召唤预加载

### 它解决什么问题

Carpet 的 `/player <名字> spawn` 在真正创建假人前要先解析玩家档案（UUID + 皮肤）。名字没缓存时会走一次网络查询，
这段时间就是「召唤时卡一下」的来源。

本规则把这一步拆成两段：

1. 收到召唤请求后立刻返回，**先在后台线程**把该玩家的档案解析好；
2. 解析完成（成功或失败）后回到服务器线程，再执行真正的召唤——此时 Carpet 查档案已经是缓存命中。

### 怎么用

```bash
/carpet fakePlayerSpawnPreload true   # 默认就是 true
```

之后照常用 `/player bot1 spawn` 即可，不需要预先配置名字列表。

### 行为细节

| 情况 | 处理 |
| --- | --- |
| 第一次召唤某个名字 | 命令立刻返回，档案解析完成后才真正召唤（通常 1 秒内） |
| 同一名字再次召唤 | 已预加载过，直接走 Carpet 原流程，不再拆分 |
| 档案解析失败（断网 / 离线服务器） | 仍然正常召唤，退回离线档案，只是没有皮肤 |
| 关闭规则 | 完全恢复 Carpet 原本的召唤流程 |

- 只影响**召唤时机**，不改变假人的 UUID、皮肤、权限等任何数据。
- 预加载在后台线程执行，不会阻塞服务器刻。
- 想确认效果可以打开 `debugLog`，日志里会记录预加载失败的个例（不影响召唤）。
## 假人 rejoin

分类：`CCU` / 假人。规则：`fakePlayerRejoinKeepRiding`（boolean，默认 `true`）。

### 命令来自 Carpet TIS Addition

`rejoin` 这个子命令不是本模组提供的，而是 **Carpet TIS Addition** 的 `fakePlayerRejoin`：

```
/player <名字> rejoin     # TIS 提供
```

它的含义是「以**存档位置**重新召唤假人」，而不是像 `spawn` 那样放到命令执行点：

| 命令 | 位置 | 骑乘 |
| --- | --- | --- |
| `/player bot1 spawn` | 命令执行点（Carpet 原版） | 下车 |
| `/player bot1 rejoin` | 存档位置（TIS） | 保留（本模组规则） |

### 本模组补什么

只补「不下车」这一块：Carpet 在 1.4.188 起，读完存档会主动 `stopRiding()` 把假人踢下載具；
本规则在 rejoin 语境下跳过这一次下坐骑，让假人继续坐在原来的载具上。

判定方式：`FabricLoader.isModLoaded("carpettisaddition")` + 反射读取 TIS 的
`FakePlayerRejoinHelper.isRejoin`，所以：

- 装了 TIS：规则生效，跟随 TIS 的 rejoin；
- 没装 TIS：规则不生效，也不会因为缺类报错。

### 实现细节

1. Carpet 的 spawn 主体跑在 `whenCompleteAsync(..., server)` 回调里，而 TIS 的命令执行完就会清掉标记，
   所以本模组在自己的 `whenCompleteAsync` 包装里把「这次是 rejoin」带进回调；
2. 召唤 lambda 里按标记跳过 `stopRiding()`，其余（背包/经验/配方等）仍由 Carpet 的 `loadPlayerData` 正常读取，
   不会重现 [Issue #2110](https://github.com/gnembon/fabric-carpet/issues/2110)；
3. `loadAndSpawnParentVehicle` 里优先复用世界里已存在的同 UUID 载具，避免重复载具。

### 关于载具偏移

代码里没有任何一行移动载具或假人的坐标：不传送载具、不重算座位、不改位置。
`/player <名字> spawn` 的行为与 Carpet 原版完全一致。

## 加规则的三步

1. `CcuRules` 加带 `@Rule` 的 `public static` 字段；
2. `assets/carpet_ccu/lang/{en_us,zh_cn}.json` 补 `carpet.rule.<字段名>.name` / `.desc`；
3. 在功能代码里读字段，并同步更新本文件。
## 紫水晶芽

分类：CCU。规则：
oAmethystBudInWater（boolean，默认 alse）。

开启后，紫水晶母岩**不会在相邻方格是水的面上生长紫水晶芽**。

- 原版 BuddingAmethystBlock#canClusterGrowAtState 对「空气」与「水源方块」都返回 true，所以母岩会在水里长芽；
  本规则在判定阶段直接对水返回 false。
- 只影响**生成新芽**：已经长出来的芽、以及芽升级成更大的芽/簇，都不受影响。
- 用 /carpet noAmethystBudInWater true 开关即可，无需重启。
