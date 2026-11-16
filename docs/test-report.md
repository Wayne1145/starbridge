# Verification log

Test session: 2026-11-15–16 (Asia/Shanghai). Local Java 17.0.8, Gradle 8.9, Forge 1.20.1-47.4.10, VS 2.4.11, KFF 4.11.0 and CC:Tweaked 1.116.2. All worlds and caches are isolated inside this project.

## Completed observations

- First full `test build`: successful, reobfuscated JAR produced; 32 initial JUnit cases passed.
- Current integrated JUnit run: 57 cases passed (flight controller, VS adapter contracts/lifecycle, blueprint generation, input policy, assembly bounds, control leases, launch handshake, physics-acknowledgement watchdog, strict JSON and request expiry).
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

Multiplayer passengers in moving cabins, 200/300-block successful flight, 10–30 simultaneous players, long-run tick budgets, cross-dimension whole-ship transfer, moving life support, rotation control, obstacle avoidance and docking. Existing flight tests cover powered translation, not those features. Galacticraft/addon content has not been ported.
