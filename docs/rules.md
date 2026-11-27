# 规则清单

命令：`/carpet <规则名> <值>`；按分类筛选：`/carpet list CCU`、`/carpet list CCU_fake_player`。

| 规则 | 类型 | 默认值 | 分类 | 说明 |
| --- | --- | --- | --- | --- |
| `debugLog` | boolean | `false` | CCU / creative / feature | 输出本模组的详细生命周期日志，仅影响日志详细程度 |
| `fakePlayerSpawnPreload` | boolean | `true` | CCU / CCU_fake_player / survival / feature | 召唤假人时先异步预加载档案再召唤，避免卡顿 |
| `fakePlayerRejoinKeepRiding` | boolean | `true` | CCU / CCU_fake_player / survival / feature | 假人重新上线（TIS rejoin / GCA 假人驻留）后回到原来乘坐的载具 |
| `commandBatchPlayerAction` | string | `"ops"` | CCU / CCU_fake_player / command | 批量假人动作的可用级别，取值同 `commandPlayer` |
| `commandPlayerLoadRange` | string | `"ops"` | CCU / CCU_fake_player / command | `/player <名字> load` 的可用级别，取值同 `commandPlayer` |

## 透明试炼方块（beta）

分类：`CCU` / `feature` / `survival`。规则：`betaTransparentNightmarishBlock`（预设，默认 `false`）。

把试炼厅里的「麻烦方块」变成幽灵方块：抗爆、不可破坏、不导电、非玩家实体可以直接穿过去。
世界吞噬者这类机器推进到宝库 / 刷怪笼 / 紫水晶母岩时，不会因为撞上这些方块卡住。

### 选项

| 值 | 生效方块 |
| --- | --- |
| `false` | 关闭（默认） |
| `trial` | 宝库 + 不祥宝库 + 试炼刷怪笼 |
| `amethyst` | 紫水晶母岩 |
| `both` | 以上全部 |

宝库与不祥宝库是同一个方块（`minecraft:vault`），靠 `ominous` 属性区分，但规则里合并成一类。

### 行为

| 行为 | 说明 |
| --- | --- |
| 抗爆 | 抗爆值按 1200 处理，TNT、苦力怕都炸不掉 |
| 不可破坏 | 破坏速度为 -1，玩家和假人都挖不动 |
| 不导电 | 不再作为红石导体 |
| 非玩家实体可穿 | TNT、矿车、掉落物、生物可以直接穿过；**玩家（含假人）仍然有碰撞** |
| 活塞推穿 | 活塞把它推掉后继续走，方块先消失 |
| 推穿后复原 | 等位置变空气、周围 6 个方块连续 2 刻稳定后，按**原状态**放回（`ominous`、`facing`、`vault_state` 都保留，不祥宝库不会退化成普通宝库） |
| 紫水晶停止生长 | 规则只要不是 `false`，紫水晶母岩就不再长新芽 |

### 复原细节

- 被推掉的方块会记在 `config/carpet-ccu-addition/nightmarish-blocks.json`，重启后继续尝试复原。
- 复原最多等 **15 分钟**：超时就写回配置、本局挂起，下次开服重新计时。
- 只处理已加载的区块：区块没加载时挂起，不会为了复原去强行加载区块。
- 方块实体（宝库的战利品表、刷怪笼配置）随方块一起保存和还原。

### 注意

| 事项 | 说明 |
| --- | --- |
| 生物也能穿 | 非玩家实体都算，僵尸等怪也能穿过宝库，不再被挡住 |
| 方块会短暂消失 | 活塞推过之后到复原之前是空气，机器可以正常通过 |
| 只支持 1.21.11 | 当前只在 1.21.11 构建验证 |

## 假人加载范围

分类：`CCU` / 假人（`CCU_fake_player`）。规则：`commandPlayerLoadRange`（string，默认 `"ops"`）。**只能调大，不能缩小。**

### 命令

命令并入 Carpet 的 `/player`，没有独立命令：

```
/player bot1 load              # 查看 bot1 当前的设置
/player bot1 load 10           # 把 bot1 的加载范围调到 10 区块（实体刻）
/player bot1 load default      # 恢复默认：跟随服务器视距
```

- 未设置的假人**不额外加载**，行为与原版一致（只按服务器 `view-distance` 加载）。
- 设置按假人**名字**保存，写在 `config/carpet-ccu-addition/load-range.json`，假人重新召唤、服务器重启后都继续生效。
- 半径范围 `1`–`32`；`default` 或 `-1` 表示清除。
- **只能调大**：填的值不大于服务器视距时不会产生额外效果，命令会直接提示。

### 为什么只能调大

原版对玩家（包括假人）的区块加载半径由服务器视距 / 模拟距离**统一**决定，没有按玩家单独配置的入口；
而区块 ticket 只能叠加、不能抵消。所以「给某个假人缩小」做不到——原版那部分 ticket 压不下去。

本功能因此只做加法：给指定假人额外挂一个区块 ticket，把它周围更多区块强制保持加载。

```
TicketType(自带 15 秒超时, 加载 + 模拟)
  → ServerChunkCache#addTicketWithRadius(type, 假人所在区块, 半径 + 2)
```

换算关系：`TicketStorage#addTicketWithRadius(type, pos, r)` 创建的 ticket 等级是 `33 - r`，
而 ticket 等级每远离一个区块 +1，实体刻要求等级 ≤ 31；因此想要「半径 R 内实体刻」需要传 `R + 2`。

### 只挂票，不撤票

区块 ticket **没有引用计数**：两个假人半径重叠时共用的是同一条 ticket，任何一个假人调用
`removeTicket` 都会把整条删掉，另一个假人的覆盖也跟着没了。以前的版本靠「每 20 刻补挂一次」
来兜这个洞，中途仍会有最多 1 秒的空窗。

现在改成**只挂不撤**：

- 每 20 刻按假人**当前所在区块**强制补挂一次，票的超时一直被续着；
- 假人移动或下线时**不撤票**，旧位置交给 15 秒超时自然回收；
- 所以「别人撤票导致自己掉加载」的情况从根上不存在了，也不需要补偿式的空窗。

### 注意

| 事项 | 说明 |
| --- | --- |
| 只能调大，不能缩小 | 原版对玩家本身就有视距范围内的 ticket，设置值 ≤ 服务器视距时效果等同未设置 |
| 旧位置会残留一会儿 | 假人移动后旧区块的票不会立刻撤销，最多多保留 15 秒 |
| 开销 | 半径每 +1，ticket 覆盖的区块按平方增长，32 区块 ≈ 4000+ 区块，注意服务器性能 |
| 生效延迟 | 新区块需要生成，半径较大时第一次生效可能要几秒到十几秒 |

## 矿车加载器

分类：`CCU` / `矿车`（`CCU_minecart`）。`/carpet list CCU_minecart` 可单独筛选。

指定种类的矿车停在指定种类的铁轨上时，**每 5 秒**给周围区块续一次加载票，保持加载与实体刻。

### 规则

| 规则 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `minecartChunkLoader` | boolean | `false` | 总开关 |
| `minecartLoaderTypes` | 预设 | `minecart` | 触发加载的矿车种类（单选）：`minecart` / `chest_minecart` / `hopper_minecart` / `tnt_minecart` / `spawner_minecart` / `command_block_minecart` / `furnace_minecart` |
| `minecartLoaderRails` | 预设 | `detector_rail` | 触发加载的铁轨种类（单选）：`detector_rail` / `rail` / `powered_rail` / `activator_rail` |
| `minecartLoaderRange` | int | `2` | 半径（区块），取值 1–32 |
| `minecartScanInterval` | int | `100` | 扫描间隔（刻）：判定铁轨、续票、发现新车都在这一轮 |
| `betaTransparentNightmarishBlock` | 预设 | `false` | CCU / feature / survival | 透明试炼方块（beta）：宝库/试炼刷怪笼/紫水晶母岩变幽灵方块 |

### 示例

写进 `world/carpet.conf`：

```conf
minecartChunkLoader true
minecartLoaderTypes minecart
minecartLoaderRails detector_rail
minecartLoaderRange 8
```

也可以在游戏里逐条设：

```
/carpet minecartLoaderTypes minecart
/carpet minecartLoaderRails detector_rail
/carpet minecartLoaderRange 8
/carpet minecartChunkLoader true
```

### 行为

**一轮扫描只做一件事的所有步骤**（默认每 `minecartScanInterval` = 100 刻 = 5 秒一次）：

1. 遍历已加载区块里的实体，按注册表 id 挑出配置的矿车种类；
2. 判定它是否在配置的铁轨上，或者正被活塞带动（`moving_piston`）；
3. 命中就按它当前所在的区块挂一次加载票，并更新锚点；
4. 本轮没命中的车走宽限 / 保活，连续失联的锚点在两分钟后丢弃。

不做逐刻探测：挂一次票要按半径覆盖 `(2R+1)²` 个区块，每刻续票的代价是每 5 秒续票的 20 倍以上。

- **矿车在铁轨上，或被活塞带动**：活塞动画期间铁轨方块会临时变成 `moving_piston`，此时判定不到
  铁轨；对已经记过锚点的矿车，这种情况按「机器在走」处理，继续续票。世界吞噬者这类整机被推着走的
  场景必须依赖这条。
- **加载票 15 秒超时**（`TicketType` timeout=300 刻，不持久化），每 5 秒续一次会刷新超时；
  矿车开走、离开铁轨或被清除后最多 15 秒自动卸载，不会在存档里留下残留。
- **静止矿车换 30 秒长票**：连续两轮续票时矿车都在同一区块（按区块比，不按精确坐标），
  说明它没在动，这一轮改用 timeout=600 刻的票。机器停着的时候多留一倍余量，
  服务器卡一下或者某轮正好错过判定窗口都不会把区块掉下去。
- **5 秒宽限**：矿车最近一次被确认在铁轨上之后 5 秒内，即使当轮判定不成立也继续续票。
  矿车整个推进周期里可能只有 2 刻停在目标铁轨上，5 秒采样就是靠这条宽限接住的——
  只要机器至少每 5 秒动一次，判定不会因为采样相位而整段丢失。
- **锚点保活**：最近 2 分钟（真实时间）内确认过的矿车，按锚点位置持续挂票。即使某几轮判定不到铁轨、
  甚至区块已经被卸载，票也不会断，矿车实体会被重新加载回来。
- **锚点过期**：连续 2 分钟没有被识别（既不在指定铁轨上、也没被活塞带动）的锚点会被丢弃并写回文件，
  重启后不再对它做自举加载。窗口特意放长，是因为 5 秒采样本来就会漏掉几轮 2 刻宽的判定窗口；
  30 秒的话会误删还在干活的矿车。
- **重启自举**：每辆成功续票的矿车记进 `config/carpet-ccu-addition/minecart-anchors.json`。
  服务器重启（或锚点区块被卸载）后，加载器先按记录把区块加载起来，把矿车找回来再照常续票；
  找不回来的锚点会自动清掉，不会留下永久加载的区块。
- 只扫描已加载区块中的矿车，不会为了找车而强行加载区块；也不会影响矿车原本的行为。
- 矿车移动后旧区块的票不会立刻撤销，最多多残留一轮票的有效期（15 或 30 秒）。
- 保活与过期都按**真实时间**计算，不受 tick rate 影响（按刻算的话，100 tps 下会被压缩 5 倍）。
- 半径换算：`addTicketWithRadius(type, pos, R + 2)`，保证半径 R 内的区块处于实体刻状态。

## 批量假人动作

### 命令

命令并入 **Carpet Org Addition** 的批量命令（没有独立命令），`/player` 的每个动作都挂成
它自带的 `spawn` / `kill` / `drop` / `trial` 的兄弟子命令：

```
/playerManager batch <前缀> <起始> <结束> <动作> [动作参数...]
```

没有安装 Carpet Org Addition 时，本功能不会注册任何命令。

Tab 补全按动作树走，和直接打 `/player <名字> ` 之后完全一致：

```
/playerManager batch bot 1 45 <TAB>         # stop use jump attack ... 全部动作
/playerManager batch bot 1 45 use <TAB>     # once continuous interval
/playerManager batch bot 1 45 look <TAB>    # north south east west up down at
```

### 目标

写法与 Carpet Org 自己的批量命令一致：名字是「前缀 + 数字」，前缀没有下划线会自动补一个，
所以 `batch bot 1 45` 对应 `bot_1` … `bot_45`；不带下划线的 `bot1` 也认，
带 TIS / GCA 名字前缀的也认（按后缀匹配）。

| 写法 | 含义 |
| --- | --- |
| `<前缀> <起始> <结束>` | `前缀_起始` … `前缀_结束`，起始大于结束会自动交换 |
| `all` 或 `*` | 所有假人（忽略区间） |

只作用于假人（`EntityPlayerMPFake`），**不会误控真人玩家**。

### 动作

动作就是 `/player <名字> <动作>` 里的那一套：节点直接从 `/player` 命令树深拷贝过来，
所以动作列表、参数、补全、权限要求全部自动同步，Carpet 以后增删动作这里也跟着变。

```bash
/playerManager batch bot 1 45 use continuous      # 批量连续右键
/playerManager batch bot 1 45 attack interval 5  # 批量每 5 刻攻击一次
/playerManager batch bot 1 45 look north          # 批量转向
/playerManager batch bot 1 45 hotbar 3            # 批量切到第 3 格
/playerManager batch all 1 1 stop                 # 全部停下
```

每条动作的回显被抑制，批量操作不会刷屏，结束时只回一行汇总：

```
已对 10 个假人执行：/player <名字> use continuous
```

### 和 Carpet Org 自带子命令的关系

| 子命令 | 归属 | 说明 |
| --- | --- | --- |
| `spawn` / `kill` / `drop` / `trial` | Carpet Org | 保留它自己的实现（例如 `kill` 走它的下班消息与分批任务），本模组不覆盖 |
| 其余动作（`stop` / `use` / `jump` / `attack` / `look` …） | 本模组 | 从 `/player` 复制，挂成上面几个的兄弟节点 |

### 不参与批量的动作

| 动作 | 原因 |
| --- | --- |
| `inventory` / `enderChest` | 「查看假人背包/末影箱」是打开界面的动作，一次开一堆没有意义；需要时对单个假人执行 `/player <名字> inventory` |

### 实现说明

- Carpet Org 的批量命令形状是 `batch <prefix> <start> <end> spawn|kill|drop|trial`，
  而它的命令名允许玩家在配置里改，所以本模组**不硬编码命令名**：服务器启动后按结构定位该节点，
  把 `/player <名字>` 下的动作节点**深拷贝**挂到 `<end>` 下面。
- 深拷贝保留原来的 `requires`、参数类型和自定义补全，只把可执行位置换成批量执行，
  因此 Tab 补全与 `/player` 一模一样。
- 挂载顺序有讲究：先挂 `/player <名字> load`，再复制动作树，否则 `load` 这个动作会被漏掉。
- 执行时按玩家实际输入截出动作原文，再逐条 `dispatcher.execute("player <名字> <动作>", 静默)`，
  参数的大小写、坐标写法都原样保留。
- 挂载在 `onServerLoaded` 做一次，未命中时在前几个 tick 重试；`/reload` 重建命令树后会重新挂载。
- 若服务器关闭了 Carpet 的 `commandPlayer` 规则，`/player` 命令树不存在，本功能不会挂载。

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

### 屏幕提示

执行者在快捷栏上方会看到进度，位置与 Carpet Org Addition 批量召唤时的提示相同，不刷聊天框：

```
正在预加载 bot1 的档案…                     ← 解析超过 0.3 秒才出现
bot1 的档案已就绪，正在召唤                  ← 解析完成、重放召唤之前
```

- **快就不显示**：档案命中缓存只要几毫秒，这时什么都不会弹，避免闪一下。
- **按人聚合**：一次召唤多个假人时显示成 `正在预加载假人档案… 已完成 3 / 10`，不会一人一条。
- **控制台 / 命令方块**触发时没有「屏幕」，只在 `debugLog` 里留下记录。
- 执行者中途下线时提示自动停掉，不会挂在那里。
## 假人上线后保留载具

分类：`CCU` / `假人`。规则：`fakePlayerRejoinKeepRiding`（boolean，默认 `true`）。

### 解决什么问题

假人重新上线时会从载具上掉下来，三条路径原来都不保留骑乘：

| 上线方式 | 命令 / 来源 | 原来的骑乘 |
| --- | --- | --- |
| `spawn` | `/player bot1 spawn`（Carpet 原版） | 下车（本规则也不管） |
| `rejoin` | `/player bot1 rejoin`（TIS） | 下车 |
| 假人驻留 | GCA 的 `fakePlayerResident`，重启后自动拉起来 | 下车 |

`rejoin` 是 **Carpet TIS Addition** 的 `fakePlayerRejoin`：以「存档位置」重新召唤假人，而不是像
`spawn` 那样放到命令执行点。`fakePlayerResident` 是 **GugleCarpetAddition**（GCA）的规则：
关服时把假人存进 `world/serverconfig/guglecarpetaddition/residents.json`，开服 `loadLevel`
结束时再拉起来。这两个功能都不是本模组提供的，本模组只补「骑回去」这一块。

### 为什么原来骑不回去

原版 1.21.9 起，玩家退出时骑着的载具会写进 playerdata 的 `RootVehicle`（载具 UUID + 完整实体数据），
由登录流程里的 `PrepareSpawnTask` 调用 `loadAndSpawnParentVehicle` 恢复。
**假人绕过整个登录流程**，所以这段恢复永远不会执行 —— `RootVehicle` 只是躺在存档里，
这就是重启 / rejoin 之后假人站在地上的原因。Carpet 自己也只是在召唤末尾补一次 `stopRiding()`。

### 本模组怎么做

1. 假人登录时读它自己的 `world/playerdata/<uuid>.dat`，取出 `RootVehicle.Attach` 里的载具 UUID；
2. 每刻查一次载具实体（它所在区块可能还没加载出来），查到且满足保护条件就让假人重新上座；
3. 只**重新上座**，不生成任何新实体 —— 载具本来就在存档里，所以不会出现重复的矿车。

判定方式：`onPlayerLoggedIn` 里判断 `player instanceof EntityPlayerMPFake`，与装没装 TIS / GCA 无关：

- 装了 GCA 的假人驻留：重启后假人被拉起，本规则把它骑回去；
- 装了 TIS：`rejoin` 之后同样走这条路径；
- 两个都没装：没有任何假人自动上线，规则自然不触发，也不会报错。

### 两个保护

| 保护 | 作用 |
| --- | --- |
| 距离 8 格以内 | 只有假人上线位置就在载具旁边才上座；`/player <名字> spawn` 到别处时不会把人拽回老位置 |
| 最多等 15 秒 | 载具区块一直没加载出来就放弃，不会无限重试（按真实时间算，不受 tick 速度影响） |

### 验证

打开 `debugLog` 后，正常恢复会看到：

```
假人 Bot1 的存档里记着载具 <uuid>，等它所在区块加载后恢复骑乘
假人 Bot1 已恢复乘坐载具 entity.minecraft.minecart @ BlockPos{x=1, y=-60, z=1}
```

放弃恢复时会打印原因：

```
假人 Bot1 等待载具 <uuid> 超过 15 秒，放弃恢复骑乘
假人 Bot1 距离载具 <uuid> 太远（超过 8 格），不恢复骑乘
```

### 关于载具偏移

代码里没有任何一行移动载具的坐标：不传送载具、不重算座位、不生成实体。
`/player <名字> spawn` 的行为与 Carpet 原版完全一致（距离保护会跳过它）。

## 加规则的三步

1. `CcuRules` 加带 `@Rule` 的 `public static` 字段；
2. `assets/carpet_ccu/lang/{en_us,zh_cn}.json` 补 `carpet.rule.<字段名>.name` / `.desc`；
3. 在功能代码里读字段，并同步更新本文件。
