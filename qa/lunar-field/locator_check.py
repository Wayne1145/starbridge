"""Read the installed original items and crafting recipes on a new zero-player dedicated server."""
import argparse
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'navigation-calibration'))
from run_server import prepare, run


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('directory', 'template', 'production', 'fixture', 'java'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    template = args.template.resolve(strict=True)
    directory = args.directory.resolve()
    launch = (template / 'launch.args').read_text('utf-8')
    if not re.search(r'--launchTarget\s+forgeserver\b', launch) or 'nogui' not in launch or 'forgeclient' in launch.lower():
        parser.error('Expected a dedicated Forge server with nogui')
    prepare(directory, template, args.production.resolve(strict=True), args.fixture.resolve(strict=True))
    (directory / 'lunar-locator-server.fixture').touch()
    findings = run(directory, args.java.resolve(strict=True), 'locator')
    print(json.dumps({'players':findings['players'],'originalItems':len(findings['legacyItems']),
                      'craftingRecipes':findings['craftingRecipes'],'dynamicCrafting':findings['dynamicTypes'],
                      'raw':str(directory/'locator-result.json')},indent=2))


if __name__ == '__main__':
    main()
