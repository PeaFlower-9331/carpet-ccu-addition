# Carpet CCU Addition

[![License](https://img.shields.io/github/license/PeaFlower-9331/carpet-ccu-addition)](https://github.com/PeaFlower-9331/carpet-ccu-addition/blob/main/LICENSE)
[![GitHub](https://img.shields.io/github/stars/PeaFlower-9331/carpet-ccu-addition?logo=github&label=GitHub)](https://github.com/PeaFlower-9331/carpet-ccu-addition)
[![MC Versions](https://img.shields.io/badge/Minecraft-1.21.10%20%7C%201.21.11-00AF5C)](https://github.com/PeaFlower-9331/carpet-ccu-addition)

[中文](README.md) | **English**

A [Carpet Mod](https://github.com/gnembon/fabric-carpet) extension focused on **fake players** and **chunk loading**.

It does not change any vanilla behaviour by default; every feature is behind a rule.

## Dependencies

| Name | Type | Link |
| --- | --- | --- |
| Carpet Mod | Required | https://github.com/gnembon/fabric-carpet |
| Fabric Loader | Required | https://fabricmc.net/ |
| Carpet Org Addition | Optional (`batch action` needs it) | https://github.com/fcsailboat/Carpet-Org-Addition |
| Carpet TIS Addition | Optional (`rejoin` and keeping the vehicle need it) | https://github.com/Fallen-Breath/fabric-carpet-tis-addition |

## Supported versions

| Minecraft | Status |
| --- | --- |
| 1.21.11 | Primary target |
| 1.21.10 | Built in parallel |

## Rules

All rules live in Carpet's main settings manager: `/carpet <rule> <value>`. Category `CCU`, sub-categories `fake_player` (`CCU_fake_player`) and `minecart` (`CCU_minecart`).

```bash
/carpet list CCU                 # all rules of this mod
/carpet list CCU_fake_player     # fake player rules
/carpet list CCU_minecart        # minecart loader rules
```

| Rule | Type | Default | Category | Description |
| --- | --- | --- | --- | --- |
| `fakePlayerSpawnPreload` | boolean | `true` | CCU / fake_player | `/player <name> spawn` preloads the profile asynchronously before spawning, avoiding a stall |
| `fakePlayerRejoinKeepRiding` | boolean | `true` | CCU / fake_player | `/player <name> rejoin` keeps the vehicle the bot was riding |
| `commandPlayerRejoin` | string | `"ops"` | CCU / fake_player / command | Permission level of `rejoin`, same values as `commandPlayer` |
| `commandBatchPlayerAction` | string | `"ops"` | CCU / fake_player / command | Permission level of the batch action command, same values as `commandPlayer` |
| `commandPlayerLoadRange` | string | `"ops"` | CCU / fake_player / command | Permission level of the load range command, same values as `commandPlayer` |
| `minecartChunkLoader` | boolean | `false` | CCU / minecart | Master switch of the minecart chunk loader |
| `minecartLoaderTypes` | string | `"minecart"` | CCU / minecart | Minecart types that trigger loading, comma separated |
| `minecartLoaderRails` | string | `"rail"` | CCU / minecart | Rail blocks that trigger loading, comma separated |
| `minecartLoaderRange` | int | `2` | CCU / minecart | Chunk radius kept loaded around the cart (1-32) |
| `noAmethystBudInWater` | boolean | `false` | CCU | Budding amethyst never grows buds on a face whose neighbour is water |
| `debugLog` | boolean | `false` | CCU | Verbose lifecycle logging |

## Commands

Both commands are merged into existing command trees; this mod registers no standalone commands.

### Batch fake player actions

Requires Carpet Org Addition. Runs any `/player` action on a group of bots:

```
/playerManager batch action <name prefix|all> <action>
```

```bash
/playerManager batch action bot use continuous     # continuous right click
/playerManager batch action bot jump               # jump
/playerManager batch action bot attack interval 5  # attack every 5 ticks
/playerManager batch action all stop               # stop everything
```

The action list is derived from the `/player` command tree at runtime; run `/playerManager batch action` without arguments to print it.

### Fake player rejoin (requires Carpet TIS Addition)

The `rejoin` command comes from Carpet TIS Addition; this mod only adds the rule that keeps the bot riding its vehicle:

```
/player bot1 rejoin
```

| Command | Position | Vehicle |
| --- | --- | --- |
| `/player bot1 spawn` | command position (vanilla Carpet) | dismounted |
| `/player bot1 rejoin` | saved position (from TIS) | kept (`fakePlayerRejoinKeepRiding`) |

Without TIS Addition the rule does nothing and never errors.

### Fake player load range

```
/player <bot> load [radius|default]
```

```bash
/player bot1 load            # query
/player bot1 load 8          # enlarge to 8 chunks (enlarge only, never below the server view distance)
/player bot1 load default    # reset
```

## Building

JDK 21 required.

```bash
./gradlew buildAndGather     # build every version in settings.json, jars are gathered into build/libs
./gradlew :1.21.11:build     # single version
```

`*-dev.jar` is for development only (not remapped) - do not ship it to a server.

## License

MIT, see [LICENSE](LICENSE).
