from pathlib import Path
import gzip, io, json, struct, zlib
from collections import Counter
import argparse


def read_compound(data):
    stream = io.BytesIO(data)
    def unpack(fmt):
        fmt = '>' + fmt
        return struct.unpack(fmt, stream.read(struct.calcsize(fmt)))[0]
    def string():
        return stream.read(unpack('H')).decode('utf-8', errors='replace')
    def value(tag):
        fmt = {1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}
        if tag in fmt:
            return unpack(fmt[tag])
        if tag == 7:
            return stream.read(unpack('i'))
        if tag == 8:
            return string()
        if tag == 9:
            subtype, length = unpack('b'), unpack('i')
            return [value(subtype) for _ in range(length)]
        if tag == 10:
            compound = {}
            while (subtype := unpack('b')):
                name = string()
                compound[name] = value(subtype)
            return compound
        if tag in (11,12):
            size = unpack('i'); fmt = 'i' if tag == 11 else 'q'
            return list(struct.unpack('>' + fmt * size, stream.read(size * (4 if tag == 11 else 8))))
        raise ValueError(f'unknown NBT tag {tag}')
    tag = unpack('b')
    string()
    return value(tag)


def chunks(region):
    data = region.read_bytes()
    for slot in range(min(len(data),4096)//4):
        start = int.from_bytes(data[4*slot:4*slot+3], 'big') * 4096
        if start:
            length = int.from_bytes(data[start:start+4], 'big')
            method = data[start+4]; payload = data[start+5:start+4+length]
            if method == 1: payload = gzip.decompress(payload)
            elif method == 2: payload = zlib.decompress(payload)
            elif method != 3: raise ValueError(f'unsupported region record {method}')
            yield read_compound(payload)


def inspect(region_dir):
    full_chunks, types, palette, entities = 0, Counter(), Counter(), []
    for region in sorted(region_dir.glob('*.mca')):
        for chunk in chunks(region):
            if chunk.get('Status') != 'minecraft:full': continue
            full_chunks += 1
            for section in chunk.get('sections', []):
                for state in section.get('block_states', {}).get('palette', []):
                    palette[state.get('Name','unknown') + str(state.get('Properties',''))] += 1
            for tile in chunk.get('block_entities', []):
                text = json.dumps(tile, default=lambda obj:f'<{len(obj)} bytes>').lower()
                types[tile.get('id','unknown')] += 1
                if any(word in text for word in ('spawner', 'treasure', 'dungeon', 'skeletonboss')):
                    entities.append(dict(position=[tile[k] for k in ('x','y','z')], tag=tile))
    return dict(full_chunks=full_chunks, tile_counts=dict(types), palette_section_counts=dict(sorted(palette.items())), interesting_tiles=entities)

if __name__=='__main__':
    parser = argparse.ArgumentParser(description='Read actual saved chunk palettes and dungeon block entities in an isolated original lunar region.')
    parser.add_argument('region',type=Path); parser.add_argument('output',type=Path)
    args=parser.parse_args()
    result=inspect(args.region)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2,default=lambda obj:obj.hex()),encoding='utf-8')
    print(result['full_chunks'],result['tile_counts'],[p['position'] for p in result['interesting_tiles']][:20])
