"""Reconstruct the lunar ship and partial-floor rider positions from the stopped releasecrew4 save."""
from __future__ import annotations
import gzip
import hashlib
import json
import math
from pathlib import Path
import sys
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from upstream_runtime_region import read_compound


def nbt(path: Path) -> dict:
    raw = path.read_bytes()
    return read_compound(gzip.decompress(raw) if raw.startswith(b"\x1f\x8b") else raw)


class SavedColumns:
    def __init__(self, region: Path):
        self.region, self.cache, self.files = region, {}, set()

    def block(self, x: int, y: int, z: int) -> str:
        cx, cz = x // 16, z // 16
        if (cx, cz) not in self.cache:
            path = self.region / f"r.{cx//32}.{cz//32}.mca"
            self.files.add(path)
            with path.open("rb") as fd:
                fd.seek(4*((cx & 31) + 32*(cz & 31)))
                sector = int.from_bytes(fd.read(4), "big") >> 8
                if not sector:
                    raise ValueError(f"Missing stored chunk {cx},{cz}")
                fd.seek(sector * 4096)
                size = int.from_bytes(fd.read(4), "big")
                if fd.read(1) != b"\x02":
                    raise ValueError("Expected a zlib Anvil chunk")
                self.cache[cx, cz] = read_compound(zlib.decompress(fd.read(size - 1)))
        chunk = self.cache[cx, cz]
        data = next((s["block_states"] for s in chunk["sections"] if s["Y"] == y//16 and "block_states" in s), None)
        if not data:
            return "minecraft:air"
        palette = data["palette"]
        if len(palette) == 1:
            return palette[0]["Name"]
        bits = max(4, (len(palette)-1).bit_length())
        per_long = 64 // bits
        i = ((y & 15)*16 + (z & 15))*16 + (x & 15)
        index = (data["data"][i//per_long] >> ((i % per_long)*bits)) & ((1 << bits)-1)
        return palette[index]["Name"]


def inspect(root: Path) -> dict:
    server = root / "server"
    world = server / "world"
    data = world / "data"
    vs_path = data / "vs_ship_data.dat"
    pipeline = json.loads(nbt(vs_path)["data"]["vs_pipeline"])
    ship, = (s for s in pipeline["ships"] if s["chunkClaimDimension"].endswith("legacy_dim_-28"))
    tr = ship["transform"]
    assert tr["shipCoordinatesToWorldCoordinatesRotation"] == dict(x=0,y=0,z=0,w=1)
    assert set(tr["shipCoordinatesToWorldCoordinatesScaling"].values()) == {1}
    shift = [tr["shipPositionInWorldCoordinates"][k] - tr["shipPositionInShipCoordinates"][k] for k in "xyz"]
    cols = SavedColumns(world / "dimensions/retrofit/legacy_dim_-28/region")
    riders = {}
    files = [vs_path, server / "flight-result.json", server / "crew-health.jsonl"]
    for profile in json.loads((server / "usercache.json").read_bytes()):
        name = profile["name"]
        if name not in ("OrbitCaptain", "OrbitEngineer"):
            continue
        playerfile = world / "playerdata" / (profile["uuid"]+".dat")
        files.append(playerfile)
        player = nbt(playerfile)
        wp = player["Pos"]
        lp = [a-b for a,b in zip(wp,shift)]
        wx,wy,wz = map(math.floor,wp)
        x,y,z = map(math.floor,lp)
        riders[name] = {"worldFeet":wp, "localFeet":lp, "grounded":bool(player["OnGround"]), "health":player["Health"],
                        "shipCellAtFeet":cols.block(x,y,z), "shipCellBelowFeet":cols.block(x,y-1,z),
                        "moonCellBelowFeet":cols.block(wx,wy-1,wz), "dimension":player["Dimension"]}
    eng = riders["OrbitEngineer"]
    assert eng["shipCellAtFeet"] == "minecraft:chest" and eng["shipCellBelowFeet"] == "minecraft:air"
    assert eng["moonCellBelowFeet"] == "galacticraftcore:tile.moonblock" and eng["health"] == 20.0
    height = math.floor(eng["localFeet"][1]) + 14/16 - eng["localFeet"][1]
    assert height == .875 and riders["OrbitCaptain"]["shipCellBelowFeet"] == "advancedrocketry:fueltank"
    counts = {}
    for file,field in [("starbridge_passages.dat","Legs"),("starbridge_orbits.dat","Legs"),("starbridge_physical_approaches.dat","Approaches")]:
        path = data / file
        tag = nbt(path)
        counts[file] = len(tag.get("data",tag)[field]); files.append(path)
    assert set(counts.values()) == {0}
    return {"run":root.name, "failedStage":json.loads((server / "flight-result.json").read_bytes())["failedStage"],
            "nativeShipId":ship["id"], "worldMinusShipTranslation":shift, "savedRiders":riders,
            "engineerBelowChestSupportBlocks":height, "journalEntriesAtStop":counts,
            "serverBacklogsMs": __import__('re').findall(r'Running (\d+)ms or \d+ ticks behind', (server / 'console.log').read_text(encoding='utf-8',errors='replace')),
            "sourceEvidenceSha256":{p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(set(files) | cols.files)}}


if __name__ == "__main__":
    print(json.dumps(inspect(Path(sys.argv[1])), indent=2))
