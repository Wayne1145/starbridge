# Verification log

A fresh survival host crafted the chart terminal and helm from supplied ingredients using vanilla recipe-book placement, opened the graphical helm recipe, placed the helm, bound the terminal and returned from its guide to the same chart. A separate no-cheat integrated-server test recorded 33 manual input ticks, a vent refill, a leak and a second full client joining over loopback. See the [results and limits](first-flight-and-lan.json) and [crafting screen](images/first-helm-recipe.png).

## Three occupied orbital ships on a dedicated server

Three stationary cabins measuring 100, 200 and 300 blocks long by 8 wide by 4 high cruised toward the original Moon. Each 1,200-tick sample saw movement from every connected account, positive progress for all three ships and 180 stone from the furnaces. The 10, 20 and 30 connection cohorts averaged 20.002, 19.999 and 19.995 TPS. At 30 connections the server tick body took 27.16 ms at p95, 32.63 ms at p99 and 51.70 ms at its peak. The server saved all dimensions and generated no crash report. Two pre-sampling startup warnings reported backlogs of 2.09 and 2.13 seconds. A single Forge process hosted the independent loopback connections. Each cohort ran for one minute on the shared test machine with view and simulation distances of 3. The test supplied the cabins, positions, energy and routes. Long-term capacity, remote latency, physical landings and graphical client frame rates need their own measurements. [Both endpoints, per-ship checkpoints, tick distribution and installed hashes](connected-fleet-verification.json) · [Reproduce the sample](../qa/connected-fleet/orbital-run.md)

## 2026-11-22 · CC:Tweaked and MCP through the graphical helm

On a dedicated Forge server, the captain paired the ship’s real CC:Tweaked advanced computer and enabled the local agent from the GUI. An unpaired Lua call failed. After pairing, its startup program read the original Galacticraft chart and moved the ship on automatic lunar approach. The external MCP process successfully held and resumed the orbit. Thrust from the captain’s cockpit displaced both external controllers, each saw a rejection, and clicking cruise allowed both to operate again. [Game, Lua and MCP evidence](computer-flight-verification.json)

## Native two-person mission to the original Moon

On a dedicated server, two survival clients boarded the same rocket at the original Advanced Rocketry assembler. The captain selected the Galacticraft Moon and a landing area in the helm. The engineer fought the original skeleton boss, picked up its key, opened the original reward menu, and moved four gunpowder from the dungeon into the ship chest. On the way home the engineer disconnected and rejoined the same cabin. The crew and original chest arrived home with both 12,000 FE receipts and no pending voyage. The test supplied the starting structure, energy, suits and combat provisions, and moved the engineer between the boss room, treasure chest and parked ship. Native chunk-resume warnings and a 15.1-second backlog occurred. The server produced no watchdog crash report. [Run data and logs](original-crew-verification.json) · [Reward menu](images/original-moon-reward-menu.png) · [Loading the ship chest](images/moon-cargo-menu.png)

The repeat using the same `03bbd62c` production JAR on all three processes returned three wheat from the chest, confirmed the two 12,000 FE payments, and restored the engineer after disconnection. The ship retained 74,948 FE. The server closed normally after both crew arrived. The largest logged backlog was 18.0 seconds. [Installed hashes, inventory and server logs](original-crew-verification.json)

## Original Moon return interrupted during payment

The old `restart3` build kept the two crew, cargo and 74,952 FE but left a paid voyage open after a dedicated server restart. With the corrected `d9cf6b9d` JAR, two full survival clients logged into a copy of that save. The original ship retained 16 diamonds, the bread from its lunar chest, eight furnace materials and the same paid receipt. The voyage cleared automatically. In a fresh trip on the same corrected build (`restart5`), the two crew used the original assembler and seats. The engineer took one original Galacticraft schematic and loaded it through the ship chest. The first JVM was terminated after the return fare was saved and before either passenger was marked delivered. Both clients rejoined the second JVM on the same ship, with the schematic, diamonds, eight furnace materials and exactly 74,958 FE remaining from 86,958. Both recovery checks passed at ten-tick sampling intervals across 100 ticks. The fresh run used a prepared shipyard, equipment, fuel, energy and dungeon positioning. The largest logged backlog was 6.895 seconds. [Ship IDs, original and restored inventory, both JVMs, both clients and file hashes](original-crew-verification.json) · [Reproduction](../qa/original-flight/README.md)

The between-process archive from `restart5` contains three of the four required region files per world. All six available chunk stamps match the paid passage, and the offline reader found the original receipt and cargo. It returned an incomplete result for the full hull. The game assertions above were made on the loaded save. A repeat with the same build (`restart6`) timed out on the outbound leg. Both crew were alive on the Moon, but the engineer stood at the bottom of the ship, below a usable deck. That save never reached the planned shutdown. Other interruption points need their own tests.

The combined build `347c4ef0` completed the original shipyard, dungeon and interrupted return in `restart7`. Between JVMs, all four hull regions per world were present. Every one of the eight saved chunk records had the same transaction as the physical receipt. The offline reader found 16 diamonds and one original Galacticraft schematic in their recorded slots, eight iron materials in the furnace, and no duplicate containers on the Moon. The return took 86,942 FE down to 74,942 FE. After restart, both clients rejoined the same living crew and ship. The journal was empty and the result held across 100 ticks. Two pre-interruption backlogs peaked at 2.423 seconds. [Both JVMs, raw region hashes and recovered inventory](original-crew-verification.json)

## Original Moon cabin oxygen and cargo return

Two non-op clients tested an Advanced Rocketry vacuum on the original Galacticraft Moon. The captain used the vent panel to refill from inventory and stayed at 20 HP in the sealed room. The exposed engineer dropped to 13.5 HP by tick 160. Opening the hatch caused genuine damage and fatigue. Resealing stopped the damage. The fixture controlled the hatch and supplied the cartridge. An earlier open-deck trip using complete original suits returned both living crew, eleven diamonds and six furnace items, with separate outbound and return receipts for 12,000 FE. The dedicated watchdog recorded a 180-second tick on that return. The voyage passed inventory and billing assertions and failed server availability. [Data, log hashes and screenshot](original-crew-verification.json) · [Reproduce the checks](../qa/original-flight/README.md)

## 2026-11-22 · Production original-world encounters

Fresh Forge 47.4.28 worlds with the five original mods and the production Starbridge seed fix generated the Galacticraft Moon dungeon. A further fix preserved the original ExtraPlanets and GalaxySpace boss-room entities while placing blocks across generation boundaries. Each of three full non-op survival clients fought a naturally spawned boss and used the dropped original key on its own treasure chest. The Ceres and Venus chests contained a tier-four rocket blueprint and a Venus rover blueprint. Two follow-up clients took those exact items, navigated the original NASA Workbench UI and unlocked pages 6666 and 6674 in Galacticraft player data. Equipment and room entry were prepared by the test fixture. [Original world results, controls and upstream item IDs](original-worlds-production.json) · [Install the pinned dependencies](original-runtime.md)

## 2026-11-22 · First flight screen and original lunar encounter

A non-op survival player in a full Forge client used an unpaired navigation slate to open First Flight, selected the shipyard tab and closed it through the normal controls. The fixture supplied equipment and placed the player in the naturally generated lunar boss room. After the client provider fix, the player fought the original Skeleton Boss, picked up its original tier-one key, unlocked the native chest and took two iron ingots through its GUI. The server positioned the character beside the chest after pickup. The test did not cover the flight to this encounter. The same profile had one connection timeout before a successful retry. [Inputs, screenshots and assertions](original-runtime-verification.json)

## 2026-11-22 · Original Galacticraft lunar dungeon

Two dedicated worlds used seed `576618501` on Forge 47.4.28. The unmodified Retrofit 0.6.2-beta did not produce Galacticraft dungeon brick in chunk (29,16). A separate fixture supplying the host world seed during terrain generation produced original dungeon brick there. The saved region contained the configured Galacticraft Skeleton Boss spawner at (396,27,193) and its locked, empty tier-one treasure chest at (369,25,190), before any player connected. The larger combined stack also loaded and saved Galacticraft's Moon on a dedicated server. [Run evidence](original-runtime-verification.json) · [Reproduction](../qa/retrofit-lunar-seed/README.md)

## 2026-11-22 · Third-person orbital helm and occupied decks

A non-op survival captain used a remapped thrust key, steered, clicked cruise and brake, selected a landing coordinate, left the cockpit and reopened it by using the ship core. The landing display kept descent disabled while the ship was outside the approach envelope. During powered flight a second survival player walked upstairs, moved three amethyst shards from the chest and opened the running furnace. Two complete game clients sent scripted input to a dedicated Forge 47.4.28 server. The fixture prepared a fixed cabin and an orbital leg. [Server results, client action logs and cockpit captures](orbital-controls-verification.json)

Run b1 repeated the trip with a 300-block-long, 6-block-wide test hull containing 1,844 actual blocks, two furnaces and one chest. Both players remained near the bow. The forward furnace smelted an iron ingot and the aft furnace at Z=299 smelted a stone block. The original rocket assembly, atmospheric handover, a wider or denser vessel, and 10–30 player operation remain separate verification items. [Reproduce the two-client scenario](../qa/orbital-cabin/README.md)

## 2026-11-22 · Advanced Rocketry native assembler on Forge 1.20.1

In a new dedicated server world, commands placed the original launchpad, tower and assembler. The fixture used Advanced Rocketry 1.4.0-32, LibVulpes 0.2.8-37 and Retrofit 0.6.2-beta on Forge 47.4.28 / Java 17.0.8. No clients connected. The fixture restored a pending upstream machine job with a known box, counter and power supply. The original machine consumed the power, scanned the blocks and decided the outcome. Automatic platform detection, the GUI buttons, survival power supply and native launch remain untested. [Runtime results and raw log references](shipyard-verification.json)

The original scanner reported missing guidance, insufficient thrust and insufficient tank capacity as the test ship changed. A 15-block ship with a loaded chest and furnace passed the scan. Assembly then stopped in `StorageChunk.copyWorldBB` because the old tile could not be reconstructed. Retrofit disabled the assembler. Both cargo slots were still at the original coordinates when rechecked, and no rocket existed. A second ship with the same layout used plain iron in those two positions. Its original machine completed the build, cleared the hull from the pad and produced an `advancedrocketry:rocket` entity. The two inventory blocks were tested together.

The follow-up with Starbridge 0.3.0-dev used a Forge FakePlayer on the dedicated server. The original button handler recognized a 3×3 platform with a six-block tower, scanned an 18-cell hull and assembled a genuine rocket. The live snapshot preserved 17 diamonds, five amethyst added after the build began, eight furnace items and the active furnace counter (21). The native seat, original flight check, powered ascent and handoff to the fixed cabin succeeded. The simulated pilot arrived aboard with the same core and cargo. The departure port stayed above the original platform while the stationary cabin was at a different location. Test setup supplied machine power and propellant. See `bridge_fix` in the [observations](shipyard-verification.json). No client was connected in this run.

A separate graphical run used a real Minecraft client, Forge 47.4.28 on both sides and an ordinary dedicated server. `ShipyardPilot` played in survival without operator privileges. The client right-clicked the original machine, changed blueprint layers, rotated the drawing, viewed the actual hull, scanned, assembled, refueled with an Advanced Rocketry bucket, boarded and launched through the visible buttons. The original rocket received 1,000 mB of propellant. The server observed it ascending under power and verified the pilot, the original rocket UUID, 17 diamonds and eight furnace items after the move into the one fixed cabin. The client then rendered the star chart. The fixture supplied the original pad, a prebuilt 18-cell hull, machine power and the full bucket. Galacticraft was absent in this profile. See `real_client_ui` in the [paired results](shipyard-verification.json) and the [illustrated player guide](shipyard.md).


Two full Forge clients then repeated the launch together on a dedicated server. Both players were in survival without operator privileges. The shipyard supplied the hull, two original seat blocks, machine power and a filled native bucket. The captain drove the original rocket while a companion seat carried the engineer. The server observed the two players together during powered ascent and separately seated in the fixed cabin. The engineer walked 1.20 blocks, opened the original ship’s chest and clicked one diamond into their inventory. The server verified 16 diamonds remained in the chest, the engineer held one, eight items remained in the furnace and the source rocket had retired. The captain’s chart, the engineer’s container and the server assertions all passed on `pair-replay-2`. [Client and server evidence](shipyard-verification.json) · [Reproduction](shipyard-fixture.md)

In another run the engineer disconnected during powered ascent at Y=196.92. The server delivered the captain to the fixed cabin while the engineer remained offline. After reconnecting to that server, the engineer arrived at their own seat and walked to the chest to take one diamond. Both actual clients and the dedicated server passed. See `real_two_client_reconnect` in the [shipyard observations](shipyard-verification.json).

A non-op survival client also repaired a genuine rejected hull. The fixture removed the original guidance computer and supplied that item in the pilot’s hotbar. Clicking **Show part** opened the third deck of the construction diagram and highlighted the missing computer. The player placed it on the ship, rescanned successfully and reached the cabin using the dock buttons. The server verified the original rejection, consumed part and successful rescan. [Image of the highlighted part](images/shipyard/07-missing-guidance.png) · [Client and server assertions](shipyard-verification.json)

## 2026-11-22 · Full-length native rocket assembly and clean restart

An ordinary Forge server assembled two-deck ships on upgraded Advanced Rocketry pads, scanned their full length, created the original rocket, fueled it through original buckets, lifted one simulated pilot into a fixed cabin and preserved the ship through a separate server process. Each ship was seven blocks wide and six high. The fixture built the hull, supplied power and propellant, kept the pad chunks loaded and set the original build timer to `0.01`. There were no clients in these scale tests. [Measurements and raw evidence](loaded-shipyard-verification.json) · [Reproduction](../qa/shipyard-load/README.md)

| Length | Occupied hull blocks / block entities | Chests / working furnaces | Diamonds / furnace inputs and outputs after restart | Slowest observed assembly tick | Slowest observed ascent and handoff tick |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 100 | 2,213 / 912 | 5 / 5 | 85 / 40 | 105 ms | 429 ms |
| 200 | 4,421 / 1,820 | 9 / 9 | 153 / 72 | 530 ms | 1,259 ms |
| 300 | 6,629 / 2,728 | 13 / 13 | 221 / 104 | 480 ms | 2,342 ms |

The first probes recorded maximum ascent ticks of 2.41, 3.14 and 3.16 seconds. The subsequent build prepared the destination during native assembly and saved the hull’s two footprints through the Forge region writer. Those runs used the native scanner’s full fuel estimate as well. All three pilots reached their cabins and all three saves reopened intact. The remaining stalls exceed a 50 ms tick budget, particularly for a 300-block hull. Wider ships, real clients tracking a native rocket at those sizes, and sustained 10–30-player operation need their own measurements.

## 2026-11-21 · Two clients took the same ship to the UnBound Moon and back

On a local dedicated server, `OrbitCaptain` and `OrbitEngineer` traveled from the Overworld to `unbound:moon` and returned aboard the same 52-block ship. Both game clients displayed the lunar berthing message. The server verified both crew members aboard at each arrival, both lunar landing journal entries, the original core UUID and VS ship ID 43. At the end, the ship belonged to the Overworld and the Moon had no core at its former storage position. [Recorded observations](expedition-verification.json)

| Checkpoint | Core energy | Crew aboard | Cargo checked by the server |
| --- | ---: | ---: | --- |
| Before departure | 200,000 FE | 2 | 17 diamonds in the chest and 8 items across the furnace input and output |
| Moon berth | 188,000 FE | 2 | Same counts. The fixture then added 5 amethyst shards to a second chest slot. |
| Home berth | 176,000 FE | 2 | 17 diamonds, 5 items in the return slot and 8 across the furnace input and output |

Each leg charged 12,000 FE. The fixture assembled a parked 7 × 7 iron deck with a flight core, chest and furnace. It seeded the journal prerequisites for both players, gave the captain the Moon discovery, placed both on deck and invoked the usual departure method on the server. The furnace started with 8 iron ore and 3 coal. Its check summed the input and output counts, leaving the smelting rate and remaining fuel unmeasured. At the Moon berth the server opened the engineer's container, wrote the shards directly and closed it. The test checked the return slot's count. Manual telescope use, client inventory clicks and use of machinery on a moving deck still need a gameplay test. [DedicatedVoyageSmoke.java](../src/main/java/dev/starbridge/testing/DedicatedVoyageSmoke.java)

During both transfers, VS logged physics frames for ship 43 while it could not find that ID. The server also reported being 2,213 ms (44 ticks) behind after lunar arrival. The run did not profile the cause or individual transfer timings. The fixture timer reached 1,440 server ticks including the wait for the crew to log in. Large crewed ships, sustained 10–30-player operation, restart recovery and continuous flight between worlds require separate tests.

After the fix for a crash between charging the core and committing its receipt, the same two-client fixture passed again (`run-expedition-pair-a6/server/voyage-result.json`). Both crew, the original ship, the cargo counts and the two 12,000 FE charges matched the first run. VS again logged late ship frames and the server reported 2,337 ms (46 ticks) behind. The on-disk unit regression reopens the ledger on both sides of billing. The 0.2 UnBound run did not interrupt a live payment. The original Galacticraft Moon test above covers that interruption on 0.3.0-dev.

The Java 17 build produced the 0.2.0-dev reobfuscated JAR. Its 63 JUnit cases and the 24 separate Python bridge cases passed.

A separate Forge GameTest run loaded UnBound and recorded 14 required cases as passed. Thirteen ran their gameplay checks. The fourteenth, `assembledCoreMovesRealShip`, explicitly logged a skip for its native-flight check. Executed cases included the UnBound journal and oxygen integration, cabin behavior and a simulated expedition from salvage and archaeology through relay construction and dispatch. The rescue test also checked that a pod from an earlier order could not complete the next rescue. This run and the two-client voyage both used Minecraft 1.20.1, Forge 47.4.28 and Java 17. The raw logs and result paths are listed in the [verification record](expedition-verification.json).

## 2026-11-15–16 · Earlier flight and integration tests

Test session: 2026-11-15–16 (Asia/Shanghai). Local Java 17.0.8, Gradle 8.9, Forge 1.20.1-47.4.10, VS 2.4.11, KFF 4.11.0 and CC:Tweaked 1.116.2. All worlds and caches are isolated inside this project.

## Completed observations

- First full `test build`: successful, reobfuscated JAR produced; 32 initial JUnit cases passed.
- Integrated JUnit run for this session: 57 cases passed (flight controller, VS adapter contracts/lifecycle, blueprint generation, input policy, assembly bounds, control leases, launch handshake, physics-acknowledgement watchdog, strict JSON and request expiry).
- Python MCP bridge: 23 cases passed, including a stdio subprocess talking to a temporary HTTP fixture. This does not certify live game control.
- Forge GameTest server starts without VS or CC installed. Datapacks and the two custom dimensions load.
- Real game test generated two 100×32×23 ships concurrently. Recorded 14,709 blocks / 25 block entities / 2716 ms and 14,685 blocks / 1 block entity / 2871 ms. The test checked aggregate direct block writes never exceed 256 per tick and that machinery contents survive generation. GameTest ticks can run faster than real time; these figures are not normal-server flight performance.
- Existing-block protection test passed; generation preserved a pre-existing gold block and refused the conflicting build.
- Exploration site idempotence and one-time reward/NBT checks passed.

## Corrected failures

- Moon height sampling now requests generated chunks first. The corresponding first-visit outpost placement bug was fixed. Re-run passed.
- Detached-core permissions are now verified separately from live authorization. Re-run passed.
- Static parking now modifies VS's authoritative server ship state. Changing only the transient physics state caused launch rollback. An ordinary-server re-run passed parked launch and HOLD.

## Real runtime results

`build/final-gametest-console.log`: eight game behavior tests passed, including real HTTP authentication/opt-in/revocation, terrain generation, site persistence, reward integrity, energy/NBT, and concurrent ship generation. The ninth GameTest explicitly skips native flight by default; the diagnostic can be reproduced with `-PnativeGameTest`. It is **not** counted as a native flight pass.

The final artifact was also rebuilt and exercised without either VS or CC installed: `build/standalone-final-console.log` reports success for all eight applicable game behavior tests, with the native flight case explicitly skipped. Optional integration startup therefore remains verified after the lifecycle and watchdog changes. The packaged JAR includes its GPL license, loader metadata, original textures and dimension data.

Native movement is verified using ordinary dedicated servers with a real local client observer, not a mocked physics API:

| Test | Blocks / block entities | Assembly | Arrival error | Mean / p95 server tick |
| --- | --- | --- | --- | --- |
| Small ship | 50 / 1 | 277 ms | 0.02784 blocks | 11.42 / 28.66 ms |
| 100-block ship, 24 machines | 14,709 / 25 | 514 ms | 0.02446 blocks | 9.50 / 25.58 ms |
| 100-block final regression, acknowledgement watchdog enabled | 14,709 / 25 | 558 ms | 0.18610 blocks | 12.14 / 36.52 ms |

Both naturally reached an eight-block waypoint, entered HOLD, stayed within the position/speed limits for 20 ticks, and consumed FE. The 100-block test used a measured external supply through the normal FE capability (100,000 FE/tick cap). Mean/p95 includes fixture preparation and assembly work; maximum tick was about 520 ms for the 100-block case. These are short single-ship observations, not a 30-player capacity result.

Primary result files: `run-smoke-arrival/starbridge-smoke-result.json` and `run-smoke-100/starbridge-smoke-result.json`.

`run-smoke-100-final/starbridge-smoke-result.json` confirms the final lifecycle/acknowledgement implementation still flies: successful native acknowledgements, no retained error, natural HOLD for 20 ticks, final speed 0.04685 blocks/s and settling drift 0.01784 blocks. These measurements vary between runs and should not be presented as fixed performance guarantees.

The client also loaded the mod in Chinese, rendered the original core texture and the generated multi-deck cabin, and successfully assembled a separate 14,685-block ship in 440 ms. It exposed the need to park newly assembled ships; that behavior is now implemented. The flight HUD itself has not yet received complete interactive visual QA.

## Unresolved evidence

- With no connected observer, native bodies did not move even during the unpowered gravity baseline. Both the GameTest and ordinary dedicated-server reproductions are retained. Do not claim unattended/offline cargo flight works.
- The first 300×64×33 test generated **124,507 blocks / 25 block entities** in 31,787 ms and assembled them in **11,882 ms**. It did not crash, but the native force callback count remained zero and waypoint travel timed out. A roughly 12-second server stall is unacceptable as a normal multiplayer interaction. This result is a failure, not supported 300-block flight.
- The 300-block run reported 20 available Java processors and an 8,531,214,336-byte max heap. It used one client observer and a measured 200,000 FE/tick test supply. No multi-client load was simulated.
- A physics-acknowledgement watchdog is implemented. In `run-smoke-300-guarded` and `run-smoke-300-lifecycle`, the drive stopped after its initial bounded grace period with a retained error, instead of endlessly purchasing force leases. Strong callback retention and early server-start subscription were also tested; neither established successful 300-block flight. In the early-subscription run callbacks occurred before the large assembly, but no force application was acknowledged afterward. The root cause is still unresolved; GC and a simple distance-only explanation were not proven.

## Not yet established

For this 0.2 physical-flight prototype, crew walking and working during powered flight, whole-ship transfers at 100–300 blocks in length, 200/300-block successful physical flight, 10–30 simultaneous players, long-run tick budgets, moving life support, rotation control, obstacle avoidance and docking remain unverified. The November 21 test covers a parked 52-block ship crossing dimensions with two passengers. Galacticraft/addon content has not been ported.
