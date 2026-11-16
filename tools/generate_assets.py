"""Generate Starbridge's original small pixel textures and an empty GameTest structure.

No third-party artwork or libraries are used. Run with Python 3.11+.
"""
from pathlib import Path
import gzip
import json
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources"


def png(path, pixels):
    path.parent.mkdir(parents=True, exist_ok=True)
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    raw = b"".join(b"\0" + bytes(channel for pixel in row for channel in (*pixel, 255)) for row in pixels)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def write_json(relative, value):
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


for face in ("side", "top", "bottom"):
    pixels = []
    for y in range(16):
        row = []
        for x in range(16):
            shade = (x * 7 + y * 13) % 9
            color = (33 + shade, 43 + shade, 53 + shade)
            if x in (0, 15) or y in (0, 15): color = (18, 24, 31)
            if x in (2, 13) and y in (2, 13): color = (145, 154, 160)
            if face == "side" and 4 <= x <= 11 and 4 <= y <= 10:
                color = (9, 25, 32)
                if y in (6, 8) and x <= (10 if y == 6 else 8): color = (68, 219, 194)
            if face == "top" and ((6 <= x <= 9 and 3 <= y <= 12) or (3 <= x <= 12 and 6 <= y <= 9)):
                color = (68, 219, 194) if x in (7, 8) or y in (7, 8) else (25, 88, 90)
            if face == "bottom" and x % 3 == 0 and 3 <= y <= 12: color = (13, 18, 24)
            row.append(color)
        pixels.append(row)
    png(ROOT / f"assets/starbridge/textures/block/ship_core_{face}.png", pixels)

write_json("assets/starbridge/blockstates/ship_core.json", {"variants": {"": {"model": "starbridge:block/ship_core"}}})
write_json("assets/starbridge/models/block/ship_core.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
    "side": "starbridge:block/ship_core_side", "top": "starbridge:block/ship_core_top", "bottom": "starbridge:block/ship_core_bottom"}})
write_json("assets/starbridge/models/item/ship_core.json", {"parent": "starbridge:block/ship_core"})
for item, texture in {"oxygen_mask": "iron_helmet", "oxygen_canister": "bucket", "survey_core": "heart_of_the_sea"}.items():
    write_json(f"assets/starbridge/models/item/{item}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"minecraft:item/{texture}"}})

write_json("data/starbridge/loot_tables/blocks/ship_core.json", {"type": "minecraft:block", "pools": [{"rolls": 1,
    "entries": [{"type": "minecraft:item", "name": "starbridge:ship_core"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
write_json("data/starbridge/recipes/ship_core.json", {"type": "minecraft:crafting_shaped", "pattern": ["IRI", "DCD", "IRI"], "key": {
    "I": {"item": "minecraft:iron_ingot"}, "R": {"item": "minecraft:redstone"}, "D": {"item": "minecraft:diamond"},
    "C": {"item": "minecraft:comparator"}}, "result": {"item": "starbridge:ship_core"}})

en = {"itemGroup.starbridge": "Starbridge", "block.starbridge.ship_core": "Vector Drive Core", "item.starbridge.oxygen_mask": "Survey Oxygen Mask",
      "item.starbridge.oxygen_canister": "Oxygen Canister", "item.starbridge.survey_core": "Survey Core",
      "message.starbridge.not_owner": "Only the ship owner or an operator can control this core.",
      "message.starbridge.core_selected": "Core selected. /starbridge pilot · /starbridge hold · /starbridge navigate x y z. Right-click with furnace fuel to charge."}
zh = {"itemGroup.starbridge": "星航", "block.starbridge.ship_core": "矢量驱动核心", "item.starbridge.oxygen_mask": "勘探供氧面罩",
      "item.starbridge.oxygen_canister": "氧气罐", "item.starbridge.survey_core": "勘探核心",
      "message.starbridge.not_owner": "只有船主或管理员可以控制该核心。",
      "message.starbridge.core_selected": "已选择核心。驾驶：/starbridge pilot；悬停：/starbridge hold；导航：/starbridge navigate x y z。手持熔炉燃料右键充能。"}
write_json("assets/starbridge/lang/en_us.json", en)
write_json("assets/starbridge/lang/zh_cn.json", zh)

def string(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data

def tag(kind, name, data):
    return bytes([kind]) + string(name) + data

empty = tag(10, "", tag(3, "DataVersion", struct.pack(">i", 3465))
    + tag(9, "size", bytes([3]) + struct.pack(">i3i", 3, 3, 3, 3))
    + tag(9, "palette", bytes([10]) + struct.pack(">i", 1) + tag(8, "Name", string("minecraft:air")) + b"\0")
    + tag(9, "blocks", bytes([10]) + struct.pack(">i", 0))
    + tag(9, "entities", bytes([10]) + struct.pack(">i", 0)) + b"\0")
structure = ROOT / "data/starbridge/structures/empty.nbt"
structure.parent.mkdir(parents=True, exist_ok=True)
structure.write_bytes(gzip.compress(empty, mtime=0))
print("Generated original core textures, models, recipes, language files and GameTest structure.")
