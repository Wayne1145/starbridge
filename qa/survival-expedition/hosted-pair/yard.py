"""Read the real launch pad, seat and workshop casting pit in the stopped camp region."""

from inspect_checkpoint import sample_blocks

REGION = "world/region/r.-1.-1.mca"
PAD = {(x, 64, z): "advancedrocketry:launchpad" for x in (-81, -80, -79) for z in (-22, -21, -20)}
PAD[-80, 65, -21] = "advancedrocketry:seat"
CAST_PIT = {(x, y, z): "minecraft:cobblestone" for x, y, z in (
    (-68, 63, -20), (-68, 64, -21), (-68, 64, -19), (-67, 64, -20), (-69, 64, -20),
)}
CAST_PIT[-68, 64, -20] = "minecraft:obsidian"


PRESS = {
    (-68, 65, -20): "minecraft:air",
    (-68, 66, -20): "advancedrocketry:tile.blockhandpress",
    (-67, 66, -20): "minecraft:lever",
}


def count_variant(inventory, item, metadata):
    return sum(stack["Count"] for stack in inventory if stack["id"] == item
               and stack.get("tag", {}).get("LegacyMeta", 0) == metadata)


def has_pressed_tin(inventory):
    return count_variant(inventory, "libvulpes:libvulpesproductplate", 5) >= 4


def has_titanium_ore(inventory):
    return count_variant(inventory, "libvulpes:libvulpesore0", 8) >= 12


def inspect_yard(region, completed, stage=None):
    press = stage in ("surface-press", "surface-power", "surface-metals", "surface-portal-stock")
    cast = press or stage == "surface-cast"
    expected = dict(PAD)
    if cast:
        expected.update(CAST_PIT)
    if press:
        expected.update(PRESS)
    actual = sample_blocks(region, list(expected))
    if completed and any(actual[pos] != block for pos, block in PAD.items()):
        raise ValueError("Original launch pad or seat missing from the closed camp region")
    if completed and cast and any(actual.get(pos) != block for pos, block in CAST_PIT.items()):
        raise ValueError("The workshop casting pit did not retain the poured obsidian")
    if completed and press and any(actual.get(pos) != block for pos, block in PRESS.items()):
        raise ValueError("The powered press must remain above the empty tin input cell")
    return [
        {"position": list(pos), "expected": block, "found": actual.get(pos)} for pos, block in expected.items()
    ]
