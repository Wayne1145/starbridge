"""Read saved lunar height, masonry and passages. Report aggregate observations only."""

from __future__ import annotations

from collections import Counter, deque
from functools import lru_cache
import gzip
import hashlib
import json
import math
from pathlib import Path
import re
import statistics
import sys
import zlib

QA = Path(__file__).resolve().parent
ROOT = QA.parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from upstream_runtime_region import read_compound  # noqa: E402

UNKNOWN, AIR, SOLID, BRICK, BOSS, CHEST, FRAGILE, DANGER, WEB = range(9)
DRY = {AIR, FRAGILE, WEB}
FLOOR = {SOLID, BRICK, BOSS, CHEST}
PAIRED = ROOT / "run-gc-pilot-ci44947273292-crew2/server/world"
NATURAL = ROOT / "run-lunar-local-near-wall-j/OrbitEngineer/saves/LunarTrail"
BASELINE = ROOT / "run-upstream-lunar-seeded-a1/world"


def kind(value):
    name = value["Name"]
    if name in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air"):
        return AIR
    if name == "galacticraftcore:tile.moonblock":
        return {"14": BRICK, "15": BOSS}.get(
            value.get("Properties", {}).get("meta"), SOLID
        )
    if name == "galacticraftcore:tile.treasurechest":
        return CHEST
    if name in (
        "galacticraftcore:tile.unlittorch",
        "minecraft:torch",
        "minecraft:wall_torch",
    ):
        return FRAGILE
    if name == "minecraft:cobweb":
        return WEB
    if name in ("minecraft:lava", "minecraft:water", "minecraft:fire"):
        return DANGER
    return SOLID


def section_data(section):
    states = section.get("block_states", {})
    palette = states.get("palette", [])
    if not palette:
        return bytes([UNKNOWN]) * 4096
    labels = bytes(map(kind, palette))
    if len(set(labels)) == 1:
        return labels[:1] * 4096
    bits = max(4, (len(palette) - 1).bit_length())
    per_word = 64 // bits
    mask = (1 << bits) - 1
    decoded = bytearray()
    for word in states["data"]:
        for j in range(min(per_word, 4096 - len(decoded))):
            decoded.append(labels[(word >> (j * bits)) & mask])
    if len(decoded) != 4096:
        raise ValueError("Incomplete section")
    return bytes(decoded)


def unpack_region(region, offset):
    start = int.from_bytes(region[4 * offset : 4 * offset + 3], "big") * 4096
    if not start:
        return None
    length = int.from_bytes(region[start : start + 4], "big")
    method = region[start + 4]
    payload = region[start + 5 : start + 4 + length]
    if method == 2:
        return zlib.decompress(payload)
    if method == 1:
        return gzip.decompress(payload)
    if method == 3:
        return payload
    raise ValueError("Unexpected region compression")


class Terrain:
    def __init__(self, world):
        self.world = world
        self.region = world / "dimensions/retrofit/legacy_dim_-28/region"
        self.sections = {}
        self.status = {}
        self.bricks = set()
        self.special = {BOSS: set(), CHEST: set()}
        self.tiles = []
        self.scanned = Counter()
        self.files = []

    @lru_cache(maxsize=12)
    def data(self, rx, rz):
        f = self.region / f"r.{rx}.{rz}.mca"
        return f.read_bytes() if f.exists() else b""

    def add(self, chunk):
        cx, cz = chunk["xPos"], chunk["zPos"]
        self.status[cx, cz] = chunk["Status"]
        slices = {}
        self.sections[cx, cz] = slices
        for stored in chunk["sections"]:
            if "block_states" not in stored:
                continue
            data = section_data(stored)
            sy = stored["Y"]
            slices[sy] = data
            if not any(v in data for v in (BRICK, BOSS, CHEST)):
                continue
            for i, v in enumerate(data):
                if v not in (BRICK, BOSS, CHEST):
                    continue
                pos = (
                    cx * 16 + (i & 15),
                    sy * 16 + (i >> 8),
                    cz * 16 + ((i >> 4) & 15),
                )
                if v == BRICK:
                    self.bricks.add(pos)
                else:
                    self.special[v].add(pos)
        for tile in chunk.get("block_entities", []):
            nested = tile.get("legacy", {})
            tag = nested.get("id")
            if tag in ("Treasure Chest", "Dungeon Boss Spawner"):
                self.tiles.append((tag, chunk["Status"], nested))

    def find_masonry(self):
        for file in sorted(self.region.glob("r.*.*.mca")):
            _, rx, rz, _ = file.name.split(".")
            rx, rz = int(rx), int(rz)
            # VS shipyard coordinates are allocated in a separate part of the world.
            if max(abs(rx), abs(rz)) > 10000:
                continue
            self.files.append(file)
            region = self.data(rx, rz)
            for offset in range(1024):
                raw = unpack_region(region, offset)
                if raw is None:
                    continue
                self.scanned["terrainChunks"] += 1
                if not any(
                    key in raw
                    for key in (
                        b"\x08\x00\x04meta\x00\x0214",
                        b"Dungeon Boss Spawner",
                        b"Treasure Chest",
                    )
                ):
                    continue
                self.add(read_compound(raw))

    def chunk(self, cx, cz):
        if (cx, cz) not in self.sections:
            region = self.data(cx // 32, cz // 32)
            raw = unpack_region(region, (cx % 32) + 32 * (cz % 32)) if region else None
            if raw:
                self.add(read_compound(raw))
            else:
                self.sections[cx, cz] = {}
                self.status[cx, cz] = "missing"
        return self.sections[cx, cz]

    def get(self, x, y, z):
        data = self.chunk(x // 16, z // 16).get(y // 16)
        return (
            UNKNOWN if data is None else data[(y % 16) * 256 + (z % 16) * 16 + x % 16]
        )

    @lru_cache(maxsize=160000)
    def top(self, x, z):
        sections = self.chunk(x // 16, z // 16)
        if not sections:
            return None
        local = (z % 16) * 16 + x % 16
        for sy in sorted(sections, reverse=True):
            if sy < 0:
                break
            data = sections[sy]
            if data == bytes([AIR]) * 4096:
                continue
            for pos in range(15, -1, -1):
                tile = data[pos * 256 + local]
                if tile not in DRY and tile != UNKNOWN:
                    return sy * 16 + pos
        return None

    def stand(self, x, y, z, passable=DRY):
        return (
            self.get(x, y - 1, z) in FLOOR
            and self.get(x, y, z) in passable
            and self.get(x, y + 1, z) in passable
        )


def seed_tag(world):
    level = read_compound(gzip.decompress((world / "level.dat").read_bytes()))
    return hashlib.sha256(
        str(level["Data"]["WorldGenSettings"]["seed"]).encode()
    ).hexdigest()


CARDINAL = ((-1, 0), (1, 0), (0, -1), (0, 1))


def shaft(t):
    # Native dungeon flooring with a completely open column to the sky provides an independent
    # entrance anchor without estimating it from the seed or the approximate centre of a room.
    return {
        (x, y + 1, z)
        for x, y, z in t.bricks
        if t.top(x, z) == y and t.stand(x, y + 1, z)
    }


def near_object(node, targets):
    x, y, z = node
    return any(abs(x - a) + abs(z - c) == 1 and abs(y - b) <= 2 for a, b, c in targets)


def walk_component(t, starts, limits, stay_inside=False, passable=DRY):
    xmin, xmax, ymin, ymax, zmin, zmax = limits
    opened = deque(starts)
    seen = set(starts)
    outside = False
    missing = False
    while opened:
        x, y, z = opened.popleft()
        for dx, dz in CARDINAL:
            nx, nz = x + dx, z + dz
            if not (xmin <= nx <= xmax and zmin <= nz <= zmax):
                outside = True
                continue
            for dy in (0, 1, -1, -2, -3):
                ny = y + dy
                next_pos = (nx, ny, nz)
                if next_pos in seen:
                    continue
                if not ymin <= ny <= ymax:
                    continue
                if t.get(nx, ny, nz) == UNKNOWN:
                    missing = True
                    continue
                if not t.stand(nx, ny, nz, passable):
                    continue
                if dy > 0 and t.get(x, y + 2, z) not in passable:
                    continue
                if dy < 0 and any(
                    t.get(nx, h, nz) not in passable for h in range(ny + 1, y + 2)
                ):
                    continue
                if stay_inside:
                    roof = t.top(nx, nz)
                    if roof is None or roof < ny + 1:
                        continue
                seen.add(next_pos)
                opened.append(next_pos)
    return seen, outside, missing


def structure(t):
    floor = shaft(t)
    xs, ys, zs = zip(*t.bricks)
    bounds = (
        min(xs) - 12,
        max(xs) + 12,
        min(ys) - 2,
        max(ys) + 3,
        min(zs) - 12,
        max(zs) + 12,
    )
    reaches, edge, unknown = walk_component(t, floor, bounds)
    figure = {
        "nativeMasonry": len(t.bricks),
        "exposedFloorColumns": len(floor),
        "exposedFloorInFullChunks": all(
            t.status[x // 16, z // 16] == "minecraft:full" for x, y, z in floor
        ),
        "spawnerBlocks": len(t.special[BOSS]),
        "treasureBlocks": len(t.special[CHEST]),
        "walkFromShaftBottomToBoss": any(
            near_object(s, t.special[BOSS]) for s in reaches
        ),
        "walkFromShaftBottomToTreasure": any(
            near_object(s, t.special[CHEST]) for s in reaches
        ),
        "originalTiles": [
            {
                "kind": key,
                "status": status,
                **{
                    key: tile[key]
                    for key in ["tier", "isLocked", "defeated", "bossClass"]
                    if key in tile
                },
            }
            for key, status, tile in t.tiles
        ],
        "walkNodes": len(reaches),
        "touchedSearchBoundary": edge,
        "touchedUnknown": unknown,
    }
    dry_only, _, _ = walk_component(t, floor, bounds, passable=DRY - {WEB})
    figure["bossAccessibleTreatingWebsAsWalls"] = any(
        near_object(p, t.special[BOSS]) for p in dry_only
    )
    figure["treasureAccessibleTreatingWebsAsWalls"] = any(
        near_object(p, t.special[CHEST]) for p in dry_only
    )
    figure["ordinaryWalkFromLocalRimToShaftBottom"] = rim_walk(t, floor)
    return figure, floor


def xyz(s):
    if isinstance(s, list):
        return tuple(map(math.floor, s))
    nums = re.findall(r"[-+]?\d+(?:\.\d+)?", s)
    if len(nums) < 3:
        raise ValueError("Recorded observation does not contain a position")
    return tuple(math.floor(float(v)) for v in nums[-3:])


def in_view(observer, target, distance):
    # Exact horizontal chunk tracking test in 1.20.1 ChunkMap.isChunkInRange.
    dx = max(0, abs(observer[0] // 16 - target[0] // 16) - 1)
    dz = max(0, abs(observer[2] // 16 - target[2] // 16) - 1)
    return min(dx, dz) ** 2 + max(0, max(dx, dz) - 1) ** 2 < distance**2


def observations(floor):
    records = set()
    matched = False
    checks = []
    for name in ["transect-h", "wide-recon-i", "near-wall-j"]:
        root = ROOT / ("run-lunar-local-" + name)
        player = root / "OrbitEngineer"
        opts = (player / "options.txt").read_text("utf8")
        dist = int(re.search(r"^renderDistance:(\d+)$", opts, re.M)[1])
        points = set()
        for file in ("lunar-walk-live.json", "lunar-search-checkpoint.json"):
            log = json.loads((player / file).read_bytes())
            points.update(xyz(item) for item in log["sampledPath"])
            points.add(xyz(log["feet"]))
        for file, key in [
            ("visible-cave-detour.json", "steps"),
            ("cave-exit.json", "excavation"),
        ]:
            if (root / file).exists():
                points.update(
                    xyz(item["feet"])
                    for item in json.loads((root / file).read_bytes())[key]
                )
        records.update(points)
        visible = any(in_view(p, f, dist) for p in points for f in floor)
        matched |= visible
        checks.append(
            {
                "checkpoint": name,
                "uniqueRecordedPositions": len(points),
                "renderDistanceChunks": dist,
                "recordedPositionInEntranceTrackingRange": visible,
            }
        )
    return {
        "checkpoints": checks,
        "distinctRecordedPositions": len(records),
        "anyRecordedPositionInRange": matched,
        "sightlineOrDiscoveryProven": False,
    }


def cave_audit(t):
    # This is the saved terrain before the subsequent manual excavation to the surface.
    detour = json.loads(
        (ROOT / "run-lunar-local-wide-recon-i/visible-cave-detour.json").read_bytes()
    )
    player = read_compound(gzip.decompress((t.world / "level.dat").read_bytes()))[
        "Data"
    ]["Player"]
    feet = xyz(player["Pos"])
    x, y, z = feet
    starts = [
        (x, h, z)
        for h in sorted(range(y - 3, y + 4), key=lambda h: abs(h - y))
        if t.stand(x, h, z)
    ]
    if not starts:
        raise ValueError("The saved expedition feet have no supported cell nearby")
    reach, edge, unknown = walk_component(
        t, starts[:1], (x - 128, x + 128, 0, 150, z - 128, z + 128), stay_inside=True
    )
    free, air_edge, air_unknown = air_component(
        t, starts[:1], (x - 128, x + 128, 0, 150, z - 128, z + 128), covered_only=True
    )
    touches_any_brick = any(
        any(
            t.get(a + dx, b + dy, c + dz) == BRICK
            for dx, dy, dz in (
                (-1, 0, 0),
                (1, 0, 0),
                (0, 0, -1),
                (0, 0, 1),
                (0, 1, 0),
                (0, -1, 0),
            )
        )
        for a, b, c in free
    )
    return {
        "coveredTwoHighAirPositions": len(free),
        "coveredAirBordersMasonry": touches_any_brick,
        "coveredAirTouchedSearchBoundary": air_edge,
        "coveredAirTouchedUnknown": air_unknown,
        "mouthRecordedEntered": detour["mouthEntered"],
        "startUnderNaturalRoof": t.top(x, z) >= starts[0][1] + 1,
        "supportedDryCavePositions": len(reach),
        "reachedNativeMasonry": any(t.get(a, b - 1, c) == BRICK for a, b, c in reach),
        "reachedBoss": any(near_object(s, t.special[BOSS]) for s in reach),
        "reachedTreasure": any(near_object(s, t.special[CHEST]) for s in reach),
        "touchedSearchBoundary": edge,
        "touchedUnknown": unknown,
    }


def air_component(t, starts, limits, covered_only=False):
    xmin, xmax, ymin, ymax, zmin, zmax = limits
    valid = DRY
    todo = deque(starts)
    seen = set(starts)
    edge = unknown = False
    while todo:
        x, y, z = todo.popleft()
        for dx, dy, dz in (
            (-1, 0, 0),
            (1, 0, 0),
            (0, 0, -1),
            (0, 0, 1),
            (0, 1, 0),
            (0, -1, 0),
        ):
            p = (x + dx, y + dy, z + dz)
            a, b, c = p
            if p in seen:
                continue
            state = t.get(a, b, c)
            if state == UNKNOWN:
                unknown = True
                continue
            if state not in valid or t.get(a, b + 1, c) not in valid:
                continue
            if covered_only and (t.top(a, c) is None or t.top(a, c) < b + 1):
                continue
            if a < xmin or a > xmax or b < ymin or b > ymax or c < zmin or c > zmax:
                edge = True
                continue
            seen.add(p)
            todo.append(p)
    return seen, edge, unknown


def sha_bytes(content):
    return hashlib.sha256(content).hexdigest()


def rim_walk(t, floor):
    if not floor:
        return False
    xs, ys, zs = zip(*floor)
    cx = int(statistics.median(xs))
    cz = int(statistics.median(zs))
    rim = []
    for x in range(cx - 10, cx + 11):
        for z in range(cz - 10, cz + 11):
            if not 6 <= max(abs(x - cx), abs(z - cz)) <= 10:
                continue
            ground = t.top(x, z)
            if ground is not None and t.stand(x, ground + 1, z):
                rim.append((x, ground + 1, z))
    median = statistics.median(h for _, h, _ in rim)
    starts = [p for p in rim if p[1] >= median - 3]
    graph, _, _ = walk_component(
        t, starts, (cx - 15, cx + 15, min(ys) - 2, int(median) + 6, cz - 15, cz + 15)
    )
    return bool(graph & floor)
