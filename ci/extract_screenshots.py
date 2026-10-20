"""Preserve instrumented screenshots even when UTP uninstalls the target app."""
import base64
from pathlib import Path
import re
shots = {}
for path in Path('app/build/outputs/androidTest-results').rglob('logcat-*.txt'):
    for line in path.read_text(errors='replace').splitlines():
        match = re.search(r'MedReminderQA\s*:\s*([\w.-]+):(\d+)/(\d+):([A-Za-z0-9+/=]+)', line)
        if match:
            name, index, count, data = match.groups()
            item = shots.setdefault(name, {'count': int(count), 'parts': {}})
            item['parts'][int(index)] = data
output = Path('qa-screenshots')
output.mkdir(exist_ok=True)
collected = 0
for name, item in shots.items():
    if len(item['parts']) != item['count']:
        print(f'Warning: logcat dropped screenshot chunks for {name}; keeping other complete screenshots')
        continue
    data = base64.b64decode(''.join(item['parts'][i] for i in range(item['count'])), validate=True)
    assert data.startswith(b'\x89PNG\r\n\x1a\n'), f'Invalid screenshot: {name}'
    (output / name).write_bytes(data)
    collected += 1
    print(f'Collected {name}: {len(data)} bytes')
assert collected >= 1, 'Missing UI acceptance screenshots' 
