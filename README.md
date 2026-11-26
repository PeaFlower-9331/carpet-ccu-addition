# Carpet CCU Addition

[![License](https://img.shields.io/github/license/PeaFlower-9331/carpet-ccu-addition)](https://github.com/PeaFlower-9331/carpet-ccu-addition/blob/main/LICENSE)
[![GitHub](https://img.shields.io/github/stars/PeaFlower-9331/carpet-ccu-addition?logo=github&label=GitHub)](https://github.com/PeaFlower-9331/carpet-ccu-addition)
[![MC Versions](https://img.shields.io/badge/Minecraft-1.21.10%20%7C%201.21.11-00AF5C)](https://github.com/PeaFlower-9331/carpet-ccu-addition)

**中文** | [English](README_en.md)

一个 [Carpet Mod](https://github.com/gnembon/fabric-carpet) 的附属，围绕**假人**与**区块加载**提供几项服务端功能。

默认配置下不会改变任何原版行为，所有功能都由规则开关控制。

## 依赖

| 名称 | 类型 | 链接 |
| --- | --- | --- |
| Carpet Mod | 必需 | https://github.com/gnembon/fabric-carpet |
| Fabric Loader | 必需 | https://fabricmc.net/ |
| Carpet Org Addition | 可选（`batch action` 需要） | https://github.com/fcsailboat/Carpet-Org-Addition |
| Carpet TIS Addition | 可选（`rejoin` 命令与保留载具需要） | https://github.com/Fallen-Breath/fabric-carpet-tis-addition |
| GugleCarpetAddition | 可选（假人驻留 `fakePlayerResident` 配合保留载具） | https://github.com/Gu-ZT/gugle-carpet-addition |

## 支持版本

| Minecraft | 状态 |
| --- | --- |
| 1.21.11 | 主目标版本 |
| 1.21.10 | 同步构建 |

## 规则

规则全部挂在 Carpet 主设置管理器下，用 `/carpet <规则名> <值>` 读写；分类为 `CCU`，子分类为 `假人`（`CCU_fake_player`）与 `矿车`（`CCU_minecart`）。

```bash
/carpet list CCU                 # 本模组全部规则
/carpet list CCU_fake_player     # 假人相关
/carpet list CCU_minecart        # 矿车加载器
```

| 规则 | 类型 | 默认值 | 分类 | 说明 |
| --- | --- | --- | --- | --- |
| `fakePlayerSpawnPreload` | boolean | `true` | CCU / 假人 | `/player <名字> spawn` 时先异步预加载档案再召唤，速度慢时在快捷栏上方显示进度 |
| `fakePlayerRejoinKeepRiding` | boolean | `true` | CCU / 假人 | 假人重新上线（TIS `rejoin` / GCA 假人驻留）后回到原来乘坐的载具 |
| `commandBatchPlayerAction` | string | `"ops"` | CCU / 假人 / command | 批量假人动作命令的可用级别，取值同 `commandPlayer` |
| `minecartChunkLoader` | boolean | `false` | CCU / 矿车 | 矿车加载器总开关 |
| `minecartLoaderTypes` | 预设 | `minecart` | CCU / 矿车 | 触发加载的矿车种类（单选，原版七种之一） |
| `minecartLoaderRails` | 预设 | `detector_rail` | CCU / 矿车 | 触发加载的铁轨种类（单选，原版四种之一） |
| `minecartLoaderRange` | int | `2` | CCU / 矿车 | 矿车周围保持加载的区块半径（1–32） |
| `minecartScanInterval` | int | `100` | CCU / 矿车 | 扫描间隔（刻）：判定铁轨、续票、发现新车都在这一轮 |
| `debugLog` | boolean | `false` | CCU | 输出详细生命周期日志，便于排查 |

## 命令

命令都并入 Carpet / Carpet Org Addition 原有的命令树，本模组不注册独立命令。

### 批量假人动作

需要 Carpet Org Addition。`/player` 的每个动作都挂成它自带 `spawn` / `kill` / `drop` 的兄弟子命令：

```
/playerManager batch <前缀> <起始> <结束> <动作> [动作参数...]
```

```bash
/playerManager batch bot 1 45 use continuous      # 批量连续右键
/playerManager batch bot 1 45 attack interval 5  # 每 5 刻攻击一次
/playerManager batch bot 1 45 look north          # 批量转向
/playerManager batch all 1 1 stop                 # 全部停下
```

动作名与参数都有 Tab 补全，节点直接从 `/player` 命令树复制，Carpet 新增动作会自动出现。
目标写法与 Carpet Org 一致：`batch bot 1 45` 对应 `bot_1` … `bot_45`。

### 假人上线后保留载具

`rejoin` 命令由 Carpet TIS Addition 提供，假人驻留由 GugleCarpetAddition 的 `fakePlayerResident` 提供；
本模组只补规则：假人在这些情况下重新上线时**回到原来乘坐的载具**。

```
/player bot1 rejoin
```

对照：

| 命令 | 位置 | 骑乘 |
| --- | --- | --- |
| `/player bot1 spawn` | 命令执行点（Carpet 原版） | 下车 |
| `/player bot1 rejoin` | 存档位置（TIS 提供） | 保留（本模组 `fakePlayerRejoinKeepRiding`） |
| 服务器重启（GCA `fakePlayerResident`） | 存档位置 | 保留（本模组 `fakePlayerRejoinKeepRiding`） |

实现方式：从 playerdata 的 `RootVehicle` 读出载具 UUID，等它的区块加载出来后让假人重新上座；
只上座、不生成载具（不会出现重复矿车），载具必须在 8 格内且最多等 15 秒，
所以 `/player <名字> spawn` 到别处时不会把人拽回老位置。

两个前置 mod 都没装时规则不生效，也不会报错。

## 构建

需要 JDK 21。

```bash
./gradlew buildAndGather     # 构建 settings.json 里登记的所有版本，产物汇总到 build/libs
./gradlew :1.21.11:build     # 只构建单个版本
```

`*-dev.jar` 是开发用（未重映射），**不要**发到服务器。

## 目录结构

```
carpet-ccu-addition/
├── settings.json                    # 登记要构建的 MC 版本
├── settings.gradle                  # 每个版本一个子项目，共用 common.gradle
├── common.gradle                    # Loom / Carpet 依赖 / 打包
├── versions/<mcver>/gradle.properties
└── src/main/
    ├── java/com/ccu/addition/
    │   ├── CcuMod.java              # ModInitializer 入口
    │   ├── CcuServer.java           # CarpetExtension 生命周期
    │   ├── CcuRules.java            # 全部规则字段
    │   ├── CcuRuleRegistrar.java    # 规则注册进 /carpet
    │   ├── command/                 # 并入原生命令树的子命令
    │   └── feature/                 # 档案预加载 / 载具恢复 / 矿车加载器
    └── resources/assets/carpet-ccu-addition/lang/
```

## 许可证

MIT，见 [LICENSE](LICENSE)。
