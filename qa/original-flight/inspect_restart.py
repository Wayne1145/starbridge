"""Read the saved fare and actual inventory on both sides while the test server was stopped."""
import argparse
import json
from pathlib import Path
import sys
from uuid import UUID

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tools'))
from upstream_runtime_region import chunks, read_compound


def uuid_from_ints(bits):
    return str(UUID(bytes=b''.join((i & 0xffffffff).to_bytes(4,'big') for i in bits)))


def tiles(region_dir, positions):
    wanted_files={region_dir/f'r.{x//512}.{z//512}.mca' for x,_,z in positions}
    result={}
    for path in sorted(wanted_files):
        if not path.is_file(): continue
        for chunk in chunks(path):
            for entity in chunk.get('block_entities',[]):
                position=(entity.get('x'),entity.get('y'),entity.get('z'))
                if position in positions:result[position]=entity
    return result


def inspect(evidence):
    cut=json.loads((evidence/'restart-cut.json').read_bytes())
    raw=read_compound((evidence/'world/data/starbridge_passages.dat').read_bytes())
    assert len(raw['Legs'])==1
    leg=raw['Legs'][0]; p=(cut['x'],cut['y'],cut['z']); chest=(p[0]+2,p[1]-1,p[2]-1);oven=(p[0],p[1],p[2]-2)
    original=tiles(evidence/'world/dimensions/retrofit/legacy_dim_-28/region',{p,chest,oven}); home=tiles(evidence/'world/region',{p,chest,oven});core=home[p];objects=home[chest]['Items'];materials=home[oven]['Items']
    expected=cut['originalReward'];receipt=uuid_from_ints(core['PaidVoyage']);visible=[(x['Slot'],x['id'],x['Count']) for x in objects];ore_iron=sum(x['Count'] for x in materials if x['id'] in ('minecraft:iron_ingot','minecraft:iron_ore'))
    proof=leg['FareProof'];before=proof['Energy'];after=core['Energy'];fare=leg['Fare'];reward_count=sum(x['Count'] for x in objects if x['id']==expected['id'])
    result={'journalCommitted':bool(leg['Committed']),'peopleMarkedDelivered':sum(bool(item['Done']) for item in leg['Crew']),'ridersInJournal':[uuid_from_ints(item['Player']) for item in leg['Crew']],'journalId':uuid_from_ints(leg['Id']),'originalCore':uuid_from_ints(core['CoreId']),'physicalPaidVoyage':receipt,'before':before,'after':after,'fare':fare,'originalMoonCopiesAtCargoPositions':len(original),'homeChest':visible,'homeFurnaceMaterials':ore_iron,'reward':expected,'sameRewardCount':reward_count==expected['count']}
    result['passed']= len(leg['Crew'])==2 and not result['journalCommitted'] and result['peopleMarkedDelivered']==0 and receipt==cut['transaction']==result['journalId'] and before-after==fare==12000 and after==cut['energyAfter'] and result['originalCore']==cut['core'] and len(original)==0 and result['sameRewardCount'] and ore_iron==8
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('evidence',type=Path);parser.add_argument('output',type=Path);args=parser.parse_args()
    result=inspect(args.evidence);args.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n',encoding='utf8');print(json.dumps(result,ensure_ascii=False));sys.exit(0 if result['passed'] else 1)
