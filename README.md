# Carpet CCU Addition

基于 [Fabric Carpet](https://github.com/gnembon/fabric-carpet) 的服务端扩展模组（地毯附属）。

- 主目标版本：**Minecraft 1.21.11 / Fabric**
- 形态：纯服务端（单机存档同样生效，因为单机自带集成服务端）
- 规则命令：`/carpet`（本模组规则分类为 `CCU`，`/carpet list CCU` 可直接筛选）

## 已实现功能

| 规则 | 说明 |
| --- | --- |
| `ccuFakePlayerProfilePreload` + `ccuFakePlayerNames` | 假人档案预加载：启动时把假人名字登记进 `usercache.json`，`/player <名字> spawn` 直接命中，不再依赖 Mojang 查询 |
| `ccuCommandBatchPlayerAction` | 批量假人动作：`/playerManager batch action <目标> <动作>`（并入 Carpet Org Addition）或独立的 `/ccuplayer`；动作列表由 `/player` 命令树实时生成，可用 `/player` 的全部动作（背包/末影箱查看除外） |
| `ccuCommandPlayerLoadRange` | 假人加载范围：`/player <假人名> load <半径\|default>`（并入 Carpet 的 `/player`），把单个假人的加载范围**调大**（只能调大，不能缩小到服务器视距以下）；等价独立命令 `/ccuload` |
| `ccuDebugLog` | 输出本模组生命周期详细日志，便于排查 |

详见 [docs/rules.md](docs/rules.md)。

## 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | **21**（Minecraft 1.20.5+ 要求；编译 1.21.11 必须 21） |
| Gradle | 9.4.1（仓库自带 wrapper，无需手动安装） |
| Fabric Loader | 0.20.1（`gradle.properties` 可改） |
| Carpet | 1.21.11-1.4.193+v251211（1.4.194 会让 Carpet Org Addition 的 mixin 目标失配） |

## 构建

```bash
# 只构建 1.21.11
./gradlew :1.21.11:build

# 构建 settings.json 里登记的所有版本，并把成品汇总到 build/libs
./gradlew buildAndGather
```

产物在 `versions/1.21.11/build/libs/carpet-ccu-addition-0.1.0-mc1.21.11.jar`。
`*-dev.jar` 是开发用（未重映射），**不要**发到服务器。

## 目录结构

```
carpet-ccu-addition/
├── settings.json                  # 登记要构建的 MC 版本列表
├── settings.gradle                # 把每个版本include成子项目，共用 common.gradle
├── build.gradle                   # 根构建脚本 + buildAndGather 汇总任务
├── common.gradle                  # 各版本子项目共用的构建逻辑（Loom/Carpet/打包）
├── gradle.properties              # 共享属性：mod_id、版本号、loader 版本
├── versions/
│   └── 1.21.11/gradle.properties  # 该版本的 minecraft/carpet/java 版本
└── src/main/
    ├── java/carpetccu/
    │   ├── CarpetCcuAddition.java   # ModInitializer 入口
    │   ├── CarpetCcuServer.java     # CarpetExtension 生命周期
    │   ├── CcuSettings.java         # 规则注册到 /carpet
    │   └── CarpetCcuSettings.java   # 全部规则字段
    └── resources/
        ├── fabric.mod.json
        └── assets/carpet_ccu/lang/{en_us,zh_cn}.json
```

## 加一条规则（3 步）

1. `CarpetCcuSettings` 里加字段：

```java
@Rule(categories = {CCU, RuleCategory.SURVIVAL}, options = {"false", "true"}, strict = true)
public static boolean ccuMyFeature = false;
```

2. `assets/carpet_ccu/lang/en_us.json` 与 `zh_cn.json` 补上：

```json
"carpet.rule.ccuMyFeature.name": "ccuMyFeature",
"carpet.rule.ccuMyFeature.desc": "……"
```

3. 在功能代码里读 `CarpetCcuSettings.ccuMyFeature`。

> 规则挂在地毯主命名空间下，所以字段名带 `ccu` 前缀，避免和其它附属重名（重名会让 Carpet 启动直接报错）。

## 加一个 MC 版本

1. `settings.json` 的 `versions` 里加版本号，例如 `"1.21.10"`；
2. 新建 `versions/1.21.10/gradle.properties`，填 `minecraft_version` / `minecraft_dependency` / `java_version` / `carpet_version`；
3. 直接 `./gradlew :1.21.10:build`。

如果新版本 API 有差异，再在 `src/main/java/.../compat/` 下加该版本专用实现，
或按需引入预处理器（参见 `docs/reference-notes.md` 的权衡说明）。

## 本机构建环境的一个坑（仅当前机器）

这台机器上 JDK 21/25 的 `Files.isWritable()` 恒返回 `false`，导致 JDK 内置 zipfs 判定 zip 只读，
Loom 写映射缓存时抛 `ReadOnlyFileSystemException`（JDK 17 则正常）。

已用 `--patch-module` 把 `jdk.nio.zipfs.ZipFileSystem` 的可写判定改成恒真来绕过，见
`.tools/run_build.ps1`。**你自己的开发机上通常不会遇到**，直接用 IDEA / 命令行 Gradle 构建即可。

## 许可证

MIT，见 [LICENSE](LICENSE)。
