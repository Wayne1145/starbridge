# Building your own moving ship

The assembly service connects an explicit player-selected structure to Valkyrien Skies. It does not discover a ship by flood-filling into neighboring terrain, and it never assembles a pending selection automatically after a restart.

## Player workflow

1. Build the desired structure and place a Starbridge flight core inside it. Right-click your core to select it. Your player must own the core or be a server operator.
2. Stop furnaces, transfers and other machines that continually change their stored state. Keep the selection free of fluids and waterlogged blocks.
3. Run `/starbridge_assemble scan <x1> <y1> <z1> <x2> <y2> <z2>`. The corners are inclusive. The selection must include the selected core.
4. Wait for the scan report. `/starbridge_assemble status` shows phase, actual blocks, block entities and selected bounds. Inspect those numbers and the bounds.
5. Run `/starbridge_assemble confirm` only when those are the blocks you intend to move. The service revalidates the selection and writes a backup before invoking VS assembly.
6. Remain online in the same dimension until completion. The core UUID and player selection survive the block move. The ship starts **parked/static**, so it cannot fall before its pilot is ready. Wait for VS ship chunks to load, check `/starbridge status`, supply energy, then use `/starbridge launch` to engage it.

`/starbridge_assemble cancel` cancels a pending operation. Scans expire five minutes after they become ready. Leaving the server/dimension, changing the selected core, changing ownership, or losing the original core cancels the operation. A backup already being written may finish after cancellation, but it cannot trigger assembly.

## Benchmark workflow

For an operator in `starbridge:shipyard`, `/starbridge_assemble benchmark` prepares a scan of the last completed benchmark owned by that player. It uses the generator's recorded origin, core and exact placed-position list. Missing generated blocks or new extra blocks inside its bounds cause rejection. It does not immediately move anything: read the report and run `confirm` separately.

For the first physical assembly test use `/starbridge_benchmark create 100 0`. Existing benchmark furnaces intentionally run; their NBT changes during scanning and strict validation will reject that snapshot. Test moving machines after the first stable assembly or stop them before scanning.

## Limits and checks

- Each axis is 1–300 blocks, inclusive. Selection volume is at most 2,000,000 cells and non-air block count at most 150,000.
- At most four pending jobs exist at once. Scan and initial validation share a global cooperative budget of 2,048 cells / 3 ms per server tick. Individual Minecraft/VS calls can still exceed that budget.
- A block entity snapshot is limited to 1 MiB serialized NBT, with 64 MiB total block entity NBT per selection.
- The selection must be inside world height and border. Bedrock, negative-hardness blocks, `valkyrienskies:assemble_blacklist` blocks, fluids and waterlogged blocks are rejected.
- VS reserved shipyard-storage chunks are rejected, including unloaded ships. Assembly from an already assembled ship is outside this initial workflow.
- Another core in the selection must have the same recorded owner as the selected core; operator status does not silently merge other players' cores.
- Vanilla world interaction/spawn-protection checks run for selected blocks. Region-protection mods can use different rules; their permission systems are not yet universally integrated. Server operators should restrict this prototype on protected production worlds until their claim mod is tested.
- Full block states and complete block entity NBT are compared. Changes in inventories, timers, owner, fuel or machine state cause rejection; the service does not quietly accept different cargo after confirmation. Previously empty cells are also rechecked.

No explosion, deletion, rollback, cross-dimension travel or world-save restoration is performed by this service. Exact selection means a player can still intentionally include unwanted terrain; selecting the correct bounds remains necessary.

## Backup and failure behavior

Backups are written under the current run directory:

```text
starbridge-backups/assembly-<timestamp>-<random-UUID>.nbt
```

The file is gzip-compressed NBT. It records format version, source dimension, requester, core UUID, inclusive minimum/maximum, and each selected non-air block's absolute position, encoded block state and full block entity NBT. It is written to a unique `.partial` file and renamed after successful completion. No existing backup is overwritten. A failed write prevents assembly.

Disk compression runs on a dedicated worker using captured snapshots; the worker never reads a Minecraft world. Before moving blocks, the main thread performs a final full validation and invokes VS's synchronous `ShipAssembler.assembleToShip`. That final validation and assembly can pause a server tick, especially for a 300-block ship. The reported duration includes both and is not presented as proof of acceptable flight performance.

Player assembly calls the backend's explicit `startStatic=true` overload. It sets the returned `ServerShip` static immediately in the same server-thread transaction, without waiting for the ship to enter the loaded-ship collection. The backend's three-argument overload remains dynamic for physics tests that intentionally measure free fall. Parking is an explicit assembly lifecycle state, not a replacement for powered flight braking.

If VS fails after beginning its move, some source blocks may already have moved. The service reports the backup path and does **not** attempt an automatic rollback that could duplicate inventory or overwrite a surviving ship. Recovery is an explicit administrator operation: stop the server, preserve the save, inspect whether a partial/live ship remains, and restore only after resolving duplicate ownership. This version supplies a backup artifact but no automatic restore command.

The backup covers selected block states and block entities. It is not a complete world backup, passenger backup, scheduled-tick snapshot, or guarantee that every third-party mod's external machine network is portable. Whole-world backups remain necessary before experimenting in an existing world.

## Verification boundary

Three pure selection tests passed on Java 17 and cover inclusive bounds, axis/volume limits, integer overflow, and exact cell enumeration. The backend has twelve separate passing adapter-contract tests. A live VS assembly, passenger behavior, third-party machines and large multiplayer flight still require the project's runtime test matrix.

Register the module once with `AssemblyModule.register()` during common mod initialization. It owns no persistent pending queue, cleans up its chunk tickets on cancellation/shutdown, and requires a fresh scan after restart.
