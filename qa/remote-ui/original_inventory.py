"""Install the complete published pack and the user's JEI release on the GUI runner."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import shutil
import sys

from runtime import PACK, hosted_only, sha

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'linux-fleet'))
from prepare import install_dep, prepare_legacy, project_files

JEI = (
    ('jei-1.20.1-forge-15.75.0.239.jar', 'u6dRKJwZ/versions/1SxSQ29z', 6629655,
     'd4e195228cd3755dc7be52dd702090c5843011ad873ced55c5bac6c8b6616ef1'),
    ('mezz_config-1.20.1-forge-0.7.1.jar', '7tEfOcA7/versions/wcPo3ErK', 387068,
     'e7321d592293a09461f3a7dbc19d46f790c7ada63931e6f986cede87dbcbea1c'),
)


def install_pack(root, game):
    from urllib.request import urlopen

    hosted_only()
    cache = root / 'inventory-cache'
    cache.mkdir()
    files, config, _ = project_files(root / 'package', cache)
    with ThreadPoolExecutor(max_workers=4) as pool:
        for dep, jar in zip(PACK['dependencies'], pool.map(lambda dep: install_dep(dep, cache), PACK['dependencies'])):
            files[f'{dep["directory"]}/{jar.name}'] = jar
    for filename, release, size, digest in JEI:
        target = cache / filename
        with urlopen(f'https://cdn.modrinth.com/data/{release}/{filename}', timeout=40) as response:
            target.write_bytes(response.read(size + 1))
        if target.stat().st_size != size or sha(target) != digest:
            raise ValueError(f'Unexpected contents for {filename}')
        files[f'mods/{filename}'] = target
    for rel, source in files.items():
        dest = game / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, dest)
    for relative in ('config/ExtraPlanets.cfg', 'retrofit/config/ExtraPlanets.cfg'):
        path = game / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(config)
    prepare_legacy(game, client=True)
    (game / 'retrofit/retrofit.properties').write_text('download-files=never\n', 'utf8')
    return {key: sha(source) for key, source in files.items()}
