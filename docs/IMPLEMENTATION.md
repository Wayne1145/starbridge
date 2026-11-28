# Starbridge implementation tracker

Target: Minecraft 1.20.1 Forge; single player, LAN, dedicated servers; 100–300 block long ships; 10–30 players as a performance goal, not a tested capacity.

## Expedition milestone (0.2.0-dev)

The navigation slate, optional Unbound journal adapter, sealed oxygen vents, expedition campaign and VS transit service are implemented. See `voyage.md`, `campaign.md` and the runtime report for the supported combinations.

## Original prototype (0.1.0-dev)

Build a runnable original vertical slice: a ship control core, real optional Valkyrien Skies integration, deterministic autopilot, optional CC:Tweaked peripheral, optional authenticated localhost agent bridge, and one original lunar expedition with a combat reward. Establish test infrastructure before claiming large-server performance.

Implemented and exercised: owned core, FE drive, safe parked assembly and explicit launch, translated navigation with braking/HOLD, manual pilot UI, paired computer peripheral, localhost authenticated agent API, MCP bridge, lunar terrain/equipment/guardian/reward slice, 100/200/300-block staged blueprints, and test harnesses. A real 100-block ship with 24 machines passed short dedicated-server flight tests. See `test-report.md` for exact evidence and limitations.

The first 300-block native trials did not complete flight. They assembled 124,507 blocks but paused the main server for around 12 seconds and did not deliver thrust callbacks. The watchdog now terminates missing-acknowledgement control rather than continuing to burn fuel. The native import/loading path needs profiling before claiming usable 300-block ships.

## Required evidence

- Java compilation and meaningful autopilot tests.
- Dedicated/game-test startup with dimension data loading.
- Optional integrations absent: no linkage errors.
- Optional integrations present: real ship telemetry and physical movement.
- Benchmark generator must be restricted to a dedicated test dimension, count blocks and machines, and stage work across ticks.
- No claim of 100–300 block flight or 10–30 player stability without measured runtime results.
- No claim of integrated Galacticraft/ExtraPlanets/GalaxySpace content: their licenses and migrations are separate work.

## Original prototype follow-up list

Moving pressure compartments, reliable crew/ship cross-dimension transactions, docking, collision-aware route planning, original planet progression and licensed content ports, long-running multi-ship server qualification.

Next technical priorities: (1) profile native large-body loading and replace or amortize synchronous assembly, (2) verify rotating/moving cabins with actual passengers and machine compatibility, (3) integrate whole-ship orbit/planet travel with atomic save/recovery, (4) expand the original expedition slice into progression linked to ship upgrades. These are remaining implementation work, not supported features of 0.1.0-dev.
