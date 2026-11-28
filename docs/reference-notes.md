# 生态对照笔记：Carpet 与各附属的约定

写这份笔记是为了让「Carpet CCU Addition」的骨架不是拍脑袋定的，而是对齐地毯本体与主流附属的既有做法。
对照对象：**Fabric Carpet**（本体，1.21.11 分支）、**Carpet TIS Addition**、**Carpet CuO Addition**。

## 1. 扩展是怎么接进去的

Carpet 提供 `carpet.CarpetExtension` 接口，附属在自己的 `ModInitializer` 里调用
`CarpetServer.manageExtension(new XxxExtension())` 即完成注册；之后 Carpet 在服务器生命周期各节点回调：

| 回调 | 时机 |
| --- | --- |
| `onGameStarted` | 游戏启动、世界未加载前；**规则解析通常放这里** |
| `onServerLoaded` | 服务器/规则就绪，世界尚未加载 |
| `onServerLoadedWorlds` | 世界文件加载完毕 |
| `onTick` | 每游戏刻最开始 |
| `onPlayerLoggedIn` / `onPlayerLoggedOut` | 玩家进出 |
| `onServerClosed` / `onReload` | 关服 / `/reload` |
| `registerLoggers` | 注册 HUD logger |
| `scarpetApi` | 往 Scarpet 加内置函数 |
| `canHasTranslations` | 提供规则的本地化字符串 |

**版本差异（重要）**：1.21.11 分支里接口在 `carpet.CarpetExtension`，更早的版本在
`carpet.api.CarpetExtension`。跨版本移植时这一行 import 必然要改。

## 2. 规则怎么挂

三种做法都存在于生态里：

| 做法 | 代表 | 效果 |
| --- | --- | --- |
| `CarpetServer.settingsManager.parseSettingsClass(MySettings.class)` | **Carpet CuO Addition** | 规则进主 `/carpet`，配置写 `carpet.conf` |
| 自建 `SettingsManager(version, id, fancyName)` + `extensionSettingsManager()` | Carpet API 文档推荐 | 规则进 `/id` 子命令，配置写 `id.conf` |
| 自研 `CarpetRuleRegistrar` 批量注册 | Carpet TIS Addition | 等价于第一种，只是做了批量封装 |

本项目选择**第一种**：对服务器管理员最省事（一个 `/carpet` 命令管全部），
同时用 `CCU` 分类把规则聚在一起，`/carpet list CCU` 即可只看本模组的规则。

内置分类见 `carpet.api.settings.RuleCategory`：`bugfix / survival / creative / experimental /
optimization / feature / command / tnt / dispenser / scarpet / client`。
`not_vanilla` 这类分类是附属自己起的名字，自定义分类是官方允许的。

## 3. 依赖与构建

| 项目 | Carpet 本体 | TIS Addition | CuO Addition |
| --- | --- | --- | --- |
| 映射 | `officialMojangMappings()` | 官方映射 | 官方映射 |
| Loom | 1.13-SNAPSHOT | 1.19-SNAPSHOT | 1.16-SNAPSHOT |
| Gradle | 9.1.0 | 9.8.2 | 9.4.1 |
| Java | 21（26.x 起 25） | 21 | 21 |
| Carpet 依赖 | — | `masa.dy.fi/maven` | `masa.dy.fi/maven`（≥26.3 转 jitpack） |

要点：

- **必须用 Mojang 官方映射**。Carpet 自己是用 mojmap 构建的，附属如果用 Yarn，类型名对不上，编译直接失败。
- Carpet 的构建产物发布在 `https://masa.dy.fi/maven`，坐标形如
  `carpet:fabric-carpet:1.21.11-1.4.194+v260107`（`<mcver>-<carpet版本>+v<构建号>`）。
- Loom 1.16+ 要求 Gradle 9 与 JVM 21+；本项目用 Gradle 9.4.1 + Loom 1.16.3。

## 4. 多版本策略

TIS 与 CuO 都用 **preprocessor**（`com.replaymod.preprocess`，Fallen-Breath/liuyuexiaoyu1 维护的分支），
在源码里写 `//#if MC >= 12100` 这类注释，按版本节点预处理出不同源码树。

本项目**暂不引入预处理器**，改用更朴素但零额外依赖的方案：

- `settings.json` 列出版本 → `settings.gradle` 把每个版本 include 成子项目；
- 每个子项目有自己的 `gradle.properties`（MC/Carpet/Java 版本）；
- 共用同一份 `src/main` 源码树与 `common.gradle`。

优点：没有第三方 Gradle 插件依赖，版本间差异少时维护成本最低。
缺点：一旦不同版本 API 分叉明显（比如 Carpet 扩展接口换包、方法签名变化），
共享源码就需要靠「兼容层 + 版本专用源码目录」处理，不如预处理器优雅。

**什么时候该换成 preprocessor**：当需要同时支持 1.20.x 与 1.21.x 且两边代码差异超过几处时
（典型如 CuO 支持 1.20.1~26.3）。届时把 `build.gradle` 换成 preprocessor 的节点图即可，
`versions/` 的目录结构可以保留。

## 5. 其余惯例

- 语言文件放 `assets/<modid>/lang/<locale>.json`，键名 `carpet.rule.<规则名>.name/.desc/.extra.N`，
  分类键名 `carpet.category.<分类>`；通过 `Translations.getTranslationFromResourcePath` 读入。
- 规则字段必须 `public static` 且非 `final`。
- mixin 类名用「目标类 + Mixin」（同一目标有多个时加用途后缀），放在 `mixin/<目标种类>` 子包下
  （如 `mixin/block/BlockMixin`）；功能名写在类注释里，不进类名——冲突或崩溃时先要能一眼看出
  注入的是哪个类。
- 附属的 `fabric.mod.json` 里 `depends.carpet` 必须声明，否则 Carpet 未加载时会以晦涩的
  `NoClassDefFoundError` 崩掉，而不是给出友好提示。
- Loom 的 `runConfigs` 建议统一 `runDir`，换版本时复用同一套 run 目录。
