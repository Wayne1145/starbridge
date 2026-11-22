"""Check the actual Advanced Rocketry launch pad and seat in the stopped camp region."""

from inspect_checkpoint import sample_blocks

REGION = "world/region/r.-1.-1.mca"
PAD = {(x, 64, z): "advancedrocketry:launchpad" for x in (-81, -80, -79) for z in (-22, -21, -20)}
PAD[-80, 65, -21] = "advancedrocketry:seat"


def inspect_yard(region, completed):
    actual = sample_blocks(region, list(PAD))
    if completed and any(actual[pos] != block for pos, block in PAD.items()):
        raise ValueError("Original launch pad or seat missing from the closed camp region")
    return [
        {"position": list(pos), "expected": block, "found": actual.get(pos)} for pos, block in PAD.items()
    ]
