# Optional ship backend and compatibility evidence

Last inspected: 2026-11-15. This document distinguishes source/ABI inspection from actual game testing. A declared dependency or a successful Java compilation does **not** prove multiplayer, native physics, or 300-block ships work.

## Forge 47.2.18 client with Embeddium and Oculus (2026-11-21)

Forge stopped during mod discovery when both Rubidium 0.7.1 and Embeddium 0.3.31 were present. Each JAR declares the mod ID `rubidium`. Starbridge 0.2.0-dev appeared once. Disabling the standalone Rubidium JAR left Embeddium and Oculus 1.8.0 enabled. A separate Minecraft 1.20.1 instance with the remaining 15 mod JARs, Forge 47.2.18 and Java 21.0.1 reached the title screen. The game captured its menu and exited normally. This run covered client startup with default test settings. The local capture is `run-compat-embeddium-v1/screenshots/2026-11-21_22.37.31.png`.

## Candidate runtime baseline

| Component | Selected candidate | Evidence |
| --- | --- | --- |
| Minecraft / loader | 1.20.1 / Forge | Project target; Starlance's checked build targets Forge 47.4.10 |
| Valkyrien Skies | Forge 1.20.1, 2.4.11 | Published Modrinth version `pOkdwuUh`, released 2026-04-10; downloaded artifact inspected locally |
| Kotlin for Forge | 4.11.0 | Matches Starlance's build; published Modrinth version `hmeyC41q` |
| CC:Tweaked | 1.116.2 | Resolved from the official SquidDev Maven repository; VS 2.4.11 requires >=1.115.1 when CC is installed |
| Starlance | 2.0.1, optional future space integration | Published Modrinth version `3mmVvAud`; not required by Starbridge's basic flight backend |
| Ad Astra | 1.15.20, optional candidate | Version selected in Starlance's checked build; its full runtime dependency set remains a separate integration step |

VS's checked `1.20.1/main` source declares 2.4.12, while the published runtime chosen above is 2.4.11. Critical entry points were checked against the actual 2.4.11 JAR with Java 17 `javap`; they are not assumed identical just because a newer source branch exists.

The inspected VS 2.4.11 `META-INF/mods.toml` requires Minecraft 1.20.1 and supports the selected Forge 47.4.10. Create is optional, but if installed must be at least 6.0.6. ComputerCraft is optional, but if installed must be at least **1.115.1**. Therefore a combined `withShips` + `withComputer` runtime must not retain a 1.114.2 CC:Tweaked dependency merely because Starlance's build used it. VS bundles Cloth Config 11.1.106, MixinExtras 0.4.1, core API/util, Jackson, JOML primitives and other libraries through Jar-in-Jar metadata; do not install duplicate copies by default. The KFF `all` JAR contains the KFF language/library/mod components. No separate Architectury dependency is declared in this VS Forge artifact.

Download URLs and publisher-provided SHA-512 hashes:

```text
Valkyrien Skies 2.4.11
https://cdn.modrinth.com/data/V5ujR2yw/versions/pOkdwuUh/valkyrienskies-120-2.4.11.jar
sha512: 6a0d959ed4bf76f08dcad0e6a360bda842d85386a05f510069eafbcc6b46b4216cdcfd9e377f82e4266b0f07e6b74118d8382af0bafcbdd65712f8a92f16e146
observed local sha256: f99f24de62015451a047f90484cf9d25970cac350105b581c22b2d5247ebfd53

Kotlin for Forge 4.11.0
https://cdn.modrinth.com/data/ordsPcFz/versions/hmeyC41q/kotlinforforge-4.11.0-all.jar
sha512: d72707078f0c4fde78981c638ca976722f22a64ce74bdb489258790fe9886d763a7b1e0dfbc5429ddd3105d8a65d177f06242dfb1457e219e56c70dd3e8cbf2c

Starlance 2.0.1
https://cdn.modrinth.com/data/dSMrdNMg/versions/3mmVvAud/Starlance-2.0.1.jar
sha512: 4e9b8271081b55ee0f4f9c03cf343ace9bbd38b438cfdc102ab76c113115c0216a1c7d9c8bbc57cf5c474146241dca46181e4add8430e2b642ec11353db31c09
```

The KFF and Starlance hashes above are metadata observations, not claims that those files were installed or game-tested. Obtain other dependencies from their official manifests rather than copying another mod's development classpath wholesale. Starlance's build includes optional integrations that Starbridge does not need for basic flight.

## Implemented backend contract

`dev.starbridge.compat.vs.VsShipBackend` has no compile-time references to VS, Kotlin, or Minecraft. It resolves VS only when used. Without VS, `probe().available()` is false and an attempted ship operation raises a diagnostic `BackendException`. It never invents a simulated ship in place of a missing dependency.

- `inspect(level, blockPos)` resolves a loaded ship around a block in VS shipyard coordinates. A core block's position is appropriate; a player's ordinary world-space position generally is not.
- `inspectById(level, shipId)` looks up the loaded ship collection and verifies its dimension before returning telemetry.
- `Snapshot` exposes `shipId`, `name`, `dimensionId`, `massKg`, `worldPosition`, `velocity`, `angularVelocity`, `rotation`, `isStatic`; aliases `mass()` and `position()` are also present. Vectors expose `x/y/z` and rotations `x/y/z/w`.
- `gravity(level)` reads VS's `DimensionParametersResolver.dimensionMap`. Missing definitions raise an explicit error. It cannot detect an unrelated mod privately changing physics gravity without updating that resolver. The inspected VS overworld resource configures `(0,-10,0)` m/s².
- `setWorldAcceleration(level,id,ax,ay,az,ttlMillis)` installs a short-lived acceleration request. The caller checks ownership, fuel and flight limits before each refresh. TTL is **wall-clock milliseconds**, restricted to 1–2000 ms; acceleration is finite and limited to 128 m/s² in magnitude. Suggested controller refresh is every game tick with a 250–500 ms TTL.
- A single `ListenableEvent.on(Consumer)` subscription applies `mass × acceleration` **on every physics tick** at the physical body's centre of mass. No world blocks or mutable block entities are read by that callback. The acceleration already includes any gravity compensation chosen by the controller.
- Expired controls and controls for unloaded bodies are removed. A callback error revokes that body's thrust and is exposed through `lastPhysicsError()`; it does not escape into VS's physics callback.
- `physicsAcknowledged(level,id,maxAgeMillis)` requires a recent successful physical force callback for the same dimension, ship and control-stream generation. Refreshes preserve a stream; release/expiry/unload/error revoke its acknowledgements. A late callback from an earlier stream cannot authorize a newly started drive. The core uses this signal to fail closed rather than continually charge energy while native callbacks are absent. An acknowledgement proves callback delivery, not displacement or absence of VS load freezing.
- The adapter retains its callback and event-source objects until close and checks event-source identity on each refresh. If the source changes, it unregisters the old listener and subscribes to the new source. Diagnostics expose both registration and current event identities. The inspected VS 2.4.11 emitter already uses strong references; this lifecycle handling is not a claim that its listeners are weak or that garbage collection caused a particular failure.
- `startPhysicsListener()` explicitly subscribes during server startup without creating any controls or applying force. It is safe to call repeatedly, and `close()` permits a subsequent server startup to subscribe again. This makes listener lifecycle explicit; it does not establish that late subscription caused a previous runtime failure. The inspected `EventEmitterImpl.emit` iterates its concurrent listener set on each emission rather than taking a fixed startup snapshot.
- `clearWorldAcceleration`, `clearAllControls`, and `close()` revoke controls. Call `close()` on server shutdown to unregister the event listener. Requests are intentionally not persisted across restart.
- `applyWorldForce` / `applyWorldTorque` remain explicit **single physics-tick** queue operations. VS's `GameToPhysicsAdapter` drains those queues. Calling them once per game tick is not equivalent to persistent thrust and should not be used as the autopilot engine.
- `setStatic` updates the authoritative `ServerShip` flag, matching VS's own `StaticCommand`, and lets the ordinary game-to-physics pipeline propagate it. A GTPA-only toggle was observed to be overwritten by unchanged game state during launch and is not used. `setStaticInGameState` also supports an existing server ship before its physics body loads; `parkInGameState` sets that flag true. These are explicit parking/launch operations, not the normal powered braking model.

The bridge uses exact `MethodHandle` bindings for `VSGameUtilsKt`, `ValkyrienSkiesMod`, and `VsApiImpl.getPhysTickEvent`. Those Kotlin classes also contain client-only method signatures; enumerating all reflective methods can resolve client types on a dedicated server. Ship-data and event objects use cached reflective dispatch.

## Assembly and large-ship experiments

The inspected official `ShipAssembler` exposes:

```text
assembleToShip(ServerLevel, Set<BlockPos>, double scale): ServerShip
```

`backend.assemble(level, selectedBlocks, scale)` calls this API and returns the ship id. Assembly moves the original blocks into VS storage; it is not a harmless preview. The caller must authorize the operation, restrict it to an explicit selection, verify size/world border/loaded chunks/assembly blacklist, and use a disposable test region. The resulting ship can take additional ticks to enter the loaded-ship collection.

`backend.assemble(level, selectedBlocks, scale, true)` immediately parks the returned `ServerShip` before returning. Player-facing assembly uses this overload to prevent an unpowered ship falling before launch. The original three-argument overload retains dynamic behavior for deliberate physics fixtures.

Test 100, 200 and 300-block-long ships separately. Record actual non-air blocks, active block entities, inventory, passengers, dimensions, simultaneous ships, game tick timings, client frame times and native memory. Test: rotation, collision, moving cabin interaction, disconnect/reconnect, save/restart, and chunk unloading. Cross-dimension passenger/inventory integrity is a separate test; this backend does not implement or certify whole-ship teleportation.

## Verification boundary

- VS 2.4.11 JAR identity and relevant API signatures were inspected.
- `VsShipBackend.java` compiles independently with the installed Java 17 compiler.
- Twelve adapter-contract JUnit tests cover missing VS, dimensional lookup, authoritative static-state changes, repeated physics ticks, changing mass, timeout, unload, explicit revoke, invalid inputs, fault reporting, acknowledgement lifetime, event-source replacement and subscription before any controls exist. They use fake API-shaped objects, not the native physics engine.
- Live Forge/VS tests and their results belong in the project's test report. No maximum ship size, 30-player capacity or supported third-party machine list is established by this document.

## Player proximity and giant-ship testing

Loaded ship metadata and `loadedForControl()` do not alone prove that the native physics callback is active. Keep the required ship/block-entity chunks ticking and verify the per-ship physical acknowledgement. Tests must state whether a player is present, where that player is located, the simulation distance and which temporary chunk tickets are used. A no-player test is a distinct case; a passing test with an observer does not establish unattended interplanetary cargo flight.

The large-fixture smoke observer now stands above the actual flight core/bridge rather than above the hull's midpoint. For the 300-block blueprint those locations differ by about 132 blocks, beyond a nominal eight-chunk distance. This exercises a pilot near the bridge while retaining separate terrain tickets centred on the complete hull. It is a test condition, not proof that player distance caused any earlier failure: in the first 300-block run, roughly 87 million FE was replenished/consumed while physical callback counters stayed at zero, so unchanged displayed battery level did not mean the core had stopped ticking.

## Primary sources

- https://github.com/ValkyrienSkies/Valkyrien-Skies-2/tree/1.20.1/main
- `common/src/main/kotlin/org/valkyrienskies/mod/common/VSGameUtils.kt`
- `common/src/main/kotlin/org/valkyrienskies/mod/common/ValkyrienSkiesMod.kt`
- `common/src/main/kotlin/org/valkyrienskies/mod/common/util/GameToPhysicsAdapter.kt`
- `common/src/main/kotlin/org/valkyrienskies/mod/common/config/DimensionParametersResolver.kt`
- `common/src/main/kotlin/org/valkyrienskies/mod/common/assembly/ShipAssembler.kt`
- https://github.com/jcm236/Starlance — checked `build.gradle`, `gradle.properties`, and `VSCHUtils.java`
- https://github.com/TechTastic/CC-VS/tree/1.20.x/main — `ShipAPI.kt` is additional evidence of the public game-to-physics call pattern; its implementation was not copied into this project.
- https://api.modrinth.com/v2/version/pOkdwuUh
- https://api.modrinth.com/v2/version/hmeyC41q
- https://api.modrinth.com/v2/version/3mmVvAud

The runtime dependencies retain their upstream licenses. Starbridge contains an original adapter and does not bundle or relicense upstream assets.
