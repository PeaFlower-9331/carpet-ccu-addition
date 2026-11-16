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
| `fakePlayerSpawnPreload` | boolean | `true` | CCU / 假人 | `/player <名字> spawn` 时先异步预加载档案再召唤，避免召唤卡顿 |
| `commandBatchPlayerAction` | string | `"ops"` | CCU / 假人 / command | 批量假人动作命令的可用级别，取值同 `commandPlayer` |
| `commandPlayerLoadRange` | string | `"ops"` | CCU / 假人 / command | 加载范围命令的可用级别，取值同 `commandPlayer` |
| `minecartChunkLoader` | boolean | `false` | CCU / 矿车 | 矿车加载器总开关 |
| `minecartLoaderTypes` | string | `"minecart"` | CCU / 矿车 | 触发加载的矿车种类，逗号分隔 |
| `minecartLoaderRails` | string | `"rail"` | CCU / 矿车 | 触发加载的铁轨种类，逗号分隔 |
| `minecartLoaderRange` | int | `2` | CCU / 矿车 | 矿车周围保持加载的区块半径（1–32） |
| `noAmethystBudInWater` | boolean | `false` | CCU | 紫水晶母岩不在相邻方格是水的面上生长紫水晶芽 |
| `debugLog` | boolean | `false` | CCU | 输出详细生命周期日志，便于排查 |

## 命令

命令都并入 Carpet / Carpet Org Addition 原有的命令树，本模组不注册独立命令。

### 批量假人动作

需要 Carpet Org Addition。对一批假人执行任意 `/player` 动作：

```
/playerManager batch action <名字前缀|all> <动作>
```

```bash
/playerManager batch action bot use continuous     # 批量连续右键
/playerManager batch action bot jump               # 批量跳
/playerManager batch action bot attack interval 5  # 每 5 刻攻击一次
/playerManager batch action all stop               # 全部停下
```

动作列表由 `/player` 命令树实时生成，`/playerManager batch action`（不带参数）即可查看。

### 假人加载范围

```
/player <假人名> load [半径|default]
```

```bash
/player bot1 load            # 查看当前设置
/player bot1 load 8          # 调到 8 区块（只能调大，无法小于服务器视距）
/player bot1 load default    # 恢复默认
```

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
    │   └── feature/                 # 档案预加载 / 加载范围 / 矿车加载器
    └── resources/assets/carpet-ccu-addition/lang/
```

## 许可证

MIT，见 [LICENSE](LICENSE)。
