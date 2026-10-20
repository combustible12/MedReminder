import hashlib, json, pathlib, subprocess, os
root = pathlib.Path(__file__).resolve().parents[1]
metadata = json.loads((root / 'release/ready.json').read_text())
source_paths = {str(p.relative_to(root)) for p in (root / 'app/src/main').rglob('*') if p.is_file()} | {'app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties'}
assert source_paths == set(metadata['sources']), 'APK source file list changed'
for filename, expected in metadata['sources'].items():
    actual = hashlib.sha256((root / filename).read_bytes()).hexdigest()
    if actual != expected:
        raise SystemExit(f'Source changed after APK signing: {filename}')
parts = sorted((root / 'release').glob('apk.part*'))
apk = root / ('release/MedReminder-' + metadata['version'] + '.apk')
if parts:
    with apk.open('wb') as output:
        for part in parts:
            output.write(part.read_bytes())
assert hashlib.sha256(apk.read_bytes()).hexdigest() == metadata['sha256'], 'APK checksum mismatch'
sdk = pathlib.Path(os.environ['ANDROID_HOME'])
build_tools = sorted((sdk / 'build-tools').iterdir())[-1]
subprocess.run([str(build_tools / 'apksigner'), 'verify', '--verbose', str(apk)], check=True)
print('APK signature, checksum and source binding verified')
