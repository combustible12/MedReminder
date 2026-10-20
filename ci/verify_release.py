import hashlib, json, pathlib, subprocess, os
root = pathlib.Path(__file__).resolve().parents[1]
metadata = json.loads((root / 'release/ready.json').read_text())
for filename, expected in metadata['sources'].items():
    actual = hashlib.sha256((root / filename).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f'Source changed after APK signing: {filename}')
parts = sorted((root / 'release').glob('apk.part*'))
apk = root / 'release/MedReminder-1.0.0.apk'
if parts:
    with apk.open('wb') as output:
        for part in parts:
            output.write(part.read_bytes())
assert hashlib.sha256(apk.read_bytes()).hexdigest() == metadata['sha256'], 'APK checksum mismatch'
sdk = pathlib.Path(os.environ['ANDROID_HOME'])
build_tools = sorted((sdk / 'build-tools').iterdir())[-1]
subprocess.run([str(build_tools / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
print('APK signature, checksum and source binding verified')
