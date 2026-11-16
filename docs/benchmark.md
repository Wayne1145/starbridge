# Isolated large-ship benchmark yard

`BenchmarkModule.register()` registers its Forge events and operator commands. It creates no terrain in normal gameplay worlds. The `starbridge:shipyard` dimension is void; all development construction happens there, in newly reserved plots spaced 1,024 blocks apart.

## Commands

All commands require operator permission level 2 and a player command source.

```text
/starbridge_benchmark enter
/starbridge_benchmark create 100 0
/starbridge_benchmark create 200 100
/starbridge_benchmark create 300 200
/starbridge_benchmark status
/starbridge_benchmark cancel
/starbridge_benchmark return
```

`enter` asynchronously builds a fenced arrival platform in a new plot, then teleports the player there. It records the original dimension and position. `return` refuses an obstructed return position and never clears blocks in the original world.

`create` accepts only lengths 100, 200 and 300. Machine count is 0–200, with 32 by default. Each request reserves another plot and builds a tapered hollow hull, multiple decks, an internal ladder shaft, observation windows, a glazed bridge, a player-owned ship core, and the requested machine count. Alternating machines are chests and furnaces; furnaces receive smeltable raw iron and coal so the loaded-machine test has real block-entity activity. This is an operator development tool, not a survival crafting shortcut.

Successful ship completion moves the player to the bridge if they are still in the shipyard. A completed static build is **not** automatically assembled as a Valkyrien Skies ship and is **not** proof of flight performance.

## Safety and scheduling

- A monotonic plot allocator is stored as `starbridge_benchmark.dat` in overworld saved data. Completed builds also retain owner, dimensions, core location, observed block-entity count and packed positions. Normal save/restart preserves these records.
- The first pass checks every planned solid block for air. Any occupied block aborts the build before placement starts. A second check occurs immediately before every write, so edits made after preflight are not overwritten.
- Air cells are skipped, never cleared. Cancellation and failure keep already placed blocks in their reserved plot; no recursive deletion or restoration touches player work. Plots are not recycled, including after cancellation or restart. If allocation data is lost in an unclean crash, air checks still prevent overlap from overwriting existing construction.
- Generation shares a **global maximum of 256 direct block placements per server tick** across all jobs, with a cooperative **3 ms** wall-clock budget and round-robin fairness. A lazy integer cursor visits the blueprint volume; it does not allocate a coordinate for every air cell.
- Chunk generation is requested through chunk futures. Custom region tickets keep only each active job's requested chunks loaded and are released on success, cancellation, failure or server shutdown. The generator does not change persistent forced-chunk configuration.
- A single Minecraft chunk operation, block placement, block-entity callback or save allocation cannot be preempted mid-call. Consequently 3 ms is a scheduling budget, not a hard real-time guarantee. `status` reports observed generator last/peak time and overrun count rather than hiding that limitation.
- Active jobs intentionally do not resume after restart. Partial builds remain and their plots stay reserved. Start another build in a new plot. The last successfully completed ship remains available for assembly.

The generator never changes game mode or grants flight. Remain on the arrival platform while construction runs; the surrounding dimension is void.

## Assembly integration

`BenchmarkModule.getLastBuild(UUID)` returns `Optional<CompletedBuild>` from persistent saved data. `CompletedBuild.positions()` is a read-only lazy `List<BlockPos>` over packed positions of **actually placed blocks only**. `core()`, `owner()`, `blockCount()`, `blockEntityCount()`, `machineCount()` and dimensions are available without rescanning the world. Coordinates belong to `BenchmarkModule.SHIPYARD`.

The assembly service must revalidate block states and ownership: players can edit or remove blocks after completion. It must also reject repeated assembly from old coordinates. After successful assembly, call `BenchmarkModule.forgetLastBuild(UUID)` if the source-coordinate snapshot is no longer meaningful.

## Validation matrix

1. Start a disposable dedicated server and verify the void dimension codec loads.
2. Enter from an ordinary world; confirm only the fresh yard platform is generated and return restores the recorded position.
3. Create each length at 0 and 200 machines. Check actual blocks, block entities, core ownership, ladder access, furnace processing and bridge spawn clearance.
4. Place a block at a planned hull coordinate during preflight/build. Confirm abort preserves that block. Existing air-space edits are outside the blueprint and are never cleared.
5. Run two players' jobs simultaneously. Instrument writes per server tick: their combined direct placements must not exceed 256. Record measured generator timings and `/forge tps` rather than assuming the cooperative budget guarantees 20 TPS.
6. Cancel mid-generation; verify partial blocks remain, temporary tickets are released and the next plot differs.
7. Save/restart after completion and after cancellation. Confirm the allocator does not reuse the old plot and completed position snapshots remain available.
8. Only after a separately verified VS assembly: measure manual flight, collision, player movement, active machines, chunk boundaries, save/reload and dimension transfer. Client FPS and server MSPT/TPS are separate measurements.

No benchmark result for 100–300 block flight or 10–30 concurrent players is claimed by merely generating these fixtures.
