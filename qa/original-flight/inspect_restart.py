"""Check the paid hull and its inventory in the files captured between server processes."""
import argparse
import json
import re
from pathlib import Path
import sys
from uuid import UUID

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from upstream_runtime_region import chunks, read_compound


def uuid_from_ints(bits):
    return str(UUID(bytes=b''.join((part & 0xffffffff).to_bytes(4, 'big') for part in bits)))


def hull_columns(bounds):
    x0, _, z0, x1, _, z1 = bounds
    return {(x, z) for x in range(x0 // 16, x1 // 16 + 1)
                   for z in range(z0 // 16, z1 // 16 + 1)}


def region_files(columns):
    return {f'r.{x // 32}.{z // 32}.mca' for x, z in columns}


def read_side(directory, columns, cargo_positions):
    stamps, entities, missing = {}, {}, []
    for name in sorted(region_files(columns)):
        region = directory / name
        if not region.is_file():
            missing.append(name)
            continue
        for chunk in chunks(region):
            column = (chunk['xPos'], chunk['zPos'])
            if column not in columns:
                continue
            raw = chunk.get('starbridge:passage_checkpoint')
            stamps[column] = uuid_from_ints(raw) if raw else None
            for tile in chunk.get('block_entities', []):
                position = (tile.get('x'), tile.get('y'), tile.get('z'))
                if position in cargo_positions:
                    entities[position] = tile
    return stamps, entities, missing


def paid_id(tag, key):
    bits = tag.get(key)
    return uuid_from_ints(bits) if bits is not None else None


def inspect(evidence):
    cut = json.loads((evidence / 'restart-cut.json').read_bytes())
    world = evidence / 'world'
    journal = read_compound((world / 'data/starbridge_passages.dat').read_bytes())
    if len(journal['Legs']) != 1:
        raise ValueError('Expected exactly one retained passage')
    leg = journal['Legs'][0]
    if cut['source'] != 'retrofit:legacy_dim_-28' or cut['destination'] != 'minecraft:overworld':
        raise ValueError('Expected the return from the original Moon to the Overworld')
    cx, cy, cz = cut['x'], cut['y'], cut['z']
    bridge, chest, oven = (cx, cy, cz), (cx + 2, cy - 1, cz - 1), (cx, cy, cz - 2)
    positions = {bridge, chest, oven}
    cargo_columns = {(x // 16, z // 16) for x, _, z in positions}
    columns = hull_columns(leg['Bounds'])
    original, old_tiles, source_missing = read_side(world / 'dimensions/retrofit/legacy_dim_-28/region', columns, positions)
    home, new_tiles, target_missing = read_side(world / 'region', columns, positions)
    transaction = uuid_from_ints(leg['Id'])
    have_cargo_columns = cargo_columns <= original.keys() and cargo_columns <= home.keys()
    have_hull = columns == original.keys() and columns == home.keys()
    all_stamped = have_hull and all(stamp == transaction for side in (original, home) for stamp in side.values())
    core = new_tiles.get(bridge, {})
    home_chest = [(item['Slot'], item['id'], item['Count']) for item in new_tiles.get(chest, {}).get('Items', [])]
    materials = sum(item['Count'] for item in new_tiles.get(oven, {}).get('Items', [])
                    if item['id'] in ('minecraft:iron_ingot', 'minecraft:iron_ore'))
    reward = cut['originalReward']
    reward_count = sum(count for _, item_id, count in home_chest if item_id == reward['id'])
    proof = leg['FareProof']
    before, after, fare = proof['Energy'], core.get('Energy'), leg['Fare']
    receipt = paid_id(core, 'PaidVoyage')
    riders = [uuid_from_ints(item['Player']) for item in leg['Crew']]
    delivered = sum(bool(item['Done']) for item in leg['Crew'])
    original_core = paid_id(core, 'CoreId')
    # Minecraft 1.20.1 writes ItemStack SNBT in sorted key order. Preserve the exact slot,
    # ID and count even when the dungeon reward stacks with existing cargo. The live check
    # independently compares the complete stack NBT, including any custom item metadata.
    expected_chest = []
    for saved in cut['chest']:
        stack = re.fullmatch(r'(\d+) \{Count:(\d+)b,id:"([a-z0-9_.-]+:[a-z0-9_./-]+)"(?:,tag:.*)?\}', saved, re.DOTALL)
        if stack is None:
            raise ValueError(f'Unrecognized 1.20.1 cutoff stack: {saved}')
        slot, count, item = stack.groups()
        expected_chest.append((int(slot), item, int(count)))
    same_chest = sorted(home_chest) == sorted(expected_chest)
    checked = (have_cargo_columns and not old_tiles and positions <= new_tiles.keys()
               and (not proof.get('ChunkWitnesses') or all_stamped)
               and len(riders) == 2 and set(riders) == set(cut['riders'])
               and not leg['Committed'] and delivered == 0 and receipt == cut['transaction'] == transaction
               and original_core == cut['core'] and after == cut['energyAfter'] and before == cut['energyBefore']
               and before - after == fare == cut['fare'] == 12000 and same_chest
               and materials == cut['furnaceMaterialCount'] == 8)
    return {
        'journalCommitted': bool(leg['Committed']), 'peopleMarkedDelivered': delivered,
        'ridersInJournal': riders, 'journalId': transaction, 'originalCore': original_core,
        'physicalPaidVoyage': receipt, 'before': before, 'after': after, 'fare': fare,
        'allCargoColumnsAvailable': have_cargo_columns,
        'originalMoonCopiesAtCargoPositions': len(old_tiles) if have_cargo_columns else None,
        'homeChest': sorted(home_chest), 'slotIdsAndCountsMatchCut': same_chest,
        'homeFurnaceMaterials': materials if oven in new_tiles else None,
        'reward': reward, 'sameRewardCount': reward_count == reward['count'],
        'chunkWitnesses': {
            'requiredChunkPairs': len(columns), 'missingRegions': {'source': source_missing, 'target': target_missing},
            'records': {role: {str(col): stamp for col, stamp in sorted(side.items())}
                        for role, side in [('source', original), ('target', home)]},
            'allMatchPaidPassage': all_stamped,
        },
        'passed': checked,
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    result = inspect(args.evidence)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n', encoding='utf8')
    print(json.dumps(result, ensure_ascii=False))
    sys.exit(0 if result['passed'] else 1)
