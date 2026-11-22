"""Send ordinary inputs to an isolated lunar QA player's camera, then inspect its saved view."""
import json
from pathlib import Path
import re
import sys

if __name__ == '__main__':
    label, = sys.argv[1:]
    if not re.fullmatch(r'[a-z0-9-]+', label):
        raise ValueError('Invalid instance label')
    root = Path(__file__).resolve().parents[2]
    instance = root / ('run-lunar-local-' + label) / 'OrbitEngineer'
    if not (instance / 'field-operator.fixture').is_file():
        raise ValueError('This instance has no field operator')
    current = instance / 'field-input.json'
    last = json.loads(current.read_bytes())['id'] if current.is_file() else 0
    steps = json.load(sys.stdin)
    if not isinstance(steps, list) or not 1 <= len(steps) <= 80:
        raise ValueError('Send an array of 1 to 80 inputs')
    order = {'id': last + 1, 'steps': steps}
    temporary = current.with_suffix('.next')
    temporary.write_text(json.dumps(order), 'utf8')
    temporary.replace(current)
    print(order['id'], instance / 'field-operator-state.json')
