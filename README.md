# DarkbotSim

A pure-Java **simulator** for [DarkBot](https://github.com/darkbot-reloaded/DarkBot), the
memory-bot for the flash browser game DarkOrbit.

DarkBot normally reads the memory of a running Flash/browser process. `DarkbotSim`
replaces that process with an in-JVM fake memory layer (`FakeMemory`) plus a
simulated game world (`SimWorld`), so the bot, its modules and plugins can be
exercised **without the game, the browser or `DarkTanos.so`**.

> Status: experimental (0.1.0). Anchored to DarkBot commit `be4d4eaba7`
> (DarkBot 1.131.7).

## Why

- Test DarkBot modules/plugins deterministically, with reproducible NPCs, boxes
  and maps.
- Reproduce edge cases (combat, portals, cargo limits) without playing.
- CI-friendly: the contract with `BotInstaller` is covered by unit tests, no
  bot launch required.

## How it works

```
DarkBot (modules/plugins)  --read-->  IDarkBotAPI.Memory
        |                                      |
        v                                      v
   core managers/entities            SimulatorAdapter (GameAPIImpl)
                                            |
                                            +-- SimMemory/FakeMemory (AVM2 layout)
                                            +-- SimWorld (hero, NPCs, boxes, map)
                                            +-- SimulatorDirectInteraction (actions)
```

- `SimulatorAdapter` extends `GameAPIImpl` and declares `BACKGROUND_ONLY` plus
  the `DIRECT_*` capabilities, so no browser/login/window is needed.
- `FakeMemory` serves the byte layout `BotInstaller` expects (pattern search,
  root pointers, entity vectors, ...).
- `SimWorld` holds the live model and ticks combat/movement/collection.
- `ScenarioRunner` loads situations from JSON or a fluent Java API.

## Requirements

- JDK 11+ (tested with 17/21)
- Gradle 8.x (wrapper included)
- Network access for the first build (JitPack artifacts for DarkBot)

## Build

```bash
./gradlew clean build
./gradlew test
```

On Windows use `gradlew.bat`.

## Usage

1. Build the jar: `./gradlew jar` produces `build/libs/darkbot-sim-0.1.0.jar`.
2. Add the jar to DarkBot's classpath.
3. Select the simulator API in DarkBot's config
   (`BOT_SETTINGS.API_CONFIG.BROWSER_API = SIMULATOR_API`) or register
   `eu.darkbot.sim.SimulatorAdapter` via the plugin API.
4. Run DarkBot normally. The simulator auto-loads `scenarios/basic_farm.json`.

### Scenario override

```bash
# load a custom scenario file
-Dsim.scenario=path/to/scenario.json
# start with an empty world
-Dsim.scenario=none
```

### Scenario format

See `scenarios/basic_farm.json` and `src/main/resources/scenarios/basic_farm.json`.

```json
{
  "map":   { "id": 1, "width": 21000, "height": 13500 },
  "hero":  { "x": 1000, "y": 2000, "speed": 400, "hp": 100000, "shield": 10000 },
  "npcs":  [ { "preset": "streuner", "x": 1500, "y": 2000 } ],
  "boxes": [ { "type": "prometium", "x": 1200, "y": 2050, "cargoValue": 50 } ]
}
```

## Tests

```bash
./gradlew test
```

`SmokeTest` validates the `FakeMemory` byte-layout contract against
`BotInstaller` without launching the bot (pattern search, root pointers,
settings closure, hero closure, read/write roundtrip, map sync).

## Project structure

```
src/main/java/eu/darkbot/sim/   simulator implementation
src/test/java/eu/darkbot/sim/   tests
src/main/resources/scenarios/    bundled scenarios
scenarios/                      example scenarios
```

Key classes: `SimulatorAdapter`, `SimWorld`, `FakeMemory`/`SimMemory`,
`SimulatorDirectInteraction`, `ScenarioRunner`, `Offsets`.

`Offsets` is the versioned memory-layout contract; bump
`Offsets.LAYOUT_VERSION` when re-anchoring to a new DarkBot commit.

## Contributing

Issues and PRs welcome. Please run `./gradlew test` before submitting and keep
changes focused. When changing memory offsets, update `Offsets` and the smoke
tests together.

## License

[MIT](LICENSE) (c) 2026

## Acknowledgements

Built on top of
[DarkBot](https://github.com/darkbot-reloaded/DarkBot) and
[DarkBotAPI](https://github.com/darkbot-reloaded/DarkBotAPI) (LGPL-3.0).
DarkBotSim is not affiliated with the DarkBot maintainers.
