"""Bundle API-v1 audio sources and package separate Natune extensions catalogs (no JVM)."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BRANCH = 'dev'
NATUNE_BRANCH = 'dev'


def bundle_sources():
    common = (ROOT / 'lib/common.js').read_text()
    for folder in sorted((ROOT / 'extension').iterdir()):
        main = folder / 'main.js'
        if not main.exists():
            continue
        parts = [common]
        if folder.name in ('tidal', 'qobuz'):
            for filename, export in [('account.js', 'AccountProvider'), ('instance.js', 'InstanceProvider')]:
                script = (folder / filename).read_text()
                script = script.replace('module.exports = {searchTracks, getTrackStreamUrl};', 'return {searchTracks, getTrackStreamUrl};').replace('module.exports={searchTracks,getTrackStreamUrl};', 'return {searchTracks, getTrackStreamUrl};')
                parts.append(f'const {export} = (() => {{\n{script}\n}})();\n')
        if folder.name == 'archivepool':
            parts.append((ROOT / 'lib/crypto.js').read_text())
            for source, export in [('tidal', 'PoolTidal'), ('qobuz', 'PoolQobuz')]:
                script = (ROOT / f'extension/{source}/account.js').read_text()
                script = script.replace('module.exports = {searchTracks, getTrackStreamUrl};', 'return {searchTracks, getTrackStreamUrl};')
                parts.append(f'const {export} = (() => {{\n{script}\n}})();\n')
        parts.append(main.read_text())
        (folder / 'provider.js').write_text('\n'.join(parts))


def build_natune_catalog():
    entries = []
    for folder in sorted((ROOT / 'natune').iterdir()):
        if not folder.is_dir():
            continue
        target = ROOT / 'dist' / f'{folder.name}.arch'
        for args in [(str(folder), '--check'), (str(folder), str(target)), ('--inspect', str(target))]:
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/package_module.py'), *args, '--json'], capture_output=True, text=True)
            if result.returncode:
                raise SystemExit(result.stdout + result.stderr)
        meta = json.loads((folder / 'manifest.json').read_text())['module']
        entries.append({'id': meta['id'], 'name': meta['name'], 'display_name': meta.get('displayName', meta['name']),
            'version': meta['version'], 'author': meta.get('author', ''), 'description': meta['description'],
            'download_url': f'https://raw.githubusercontent.com/NatuneGroup/Natune/{NATUNE_BRANCH}/addons/modules/dist/{target.name}',
            'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'capabilities': meta['capabilities'],
            'labels': ['Natune only', 'lyrics'] if meta['type'] == 'lyrics_provider' else ['Natune only'],
            'extensionKind': meta['type']})
        print(f'{folder.name}: Natune extension checked, packaged and inspected ({target.stat().st_size} bytes)')
    (ROOT / 'natune-extensions.json').write_text(json.dumps({'name': 'Natune extensions (Android)', 'version': 1, 'extensions': entries}, indent=2) + '\n')


def main():
    bundle_sources()
    dist = ROOT / 'dist'
    dist.mkdir(exist_ok=True)
    for retired in ('tidal-instance', 'qobuz-instance'):
        (dist / f'{retired}.arch').unlink(missing_ok=True)
    entries = []
    for folder in sorted((ROOT / 'extension').iterdir()):
        target = dist / f'{folder.name}.arch'
        for args in [(str(folder), '--check'), (str(folder), str(target)), ('--inspect', str(target))]:
            result = subprocess.run([sys.executable, str(ROOT / 'scripts/package_module.py'), *args, '--json'], capture_output=True, text=True)
            if result.returncode:
                raise SystemExit(result.stdout + result.stderr)
        meta = json.loads((folder / 'manifest.json').read_text())['module']
        entries.append({'id': meta['id'], 'name': meta['name'], 'display_name': meta.get('displayName', meta['name']),
            'version': meta['version'], 'author': meta.get('author', ''), 'description': meta['description'],
            'download_url': f'https://raw.githubusercontent.com/NatuneGroup/Natune/{BRANCH}/addons/modules/dist/{target.name}',
            'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'capabilities': meta['capabilities'],
            'labels': meta.get('labels', []), 'category': 'download'})
        print(f'{folder.name}: checked, packaged and inspected ({target.stat().st_size} bytes)')
    (ROOT / 'registry.json').write_text(json.dumps({'name':'Natune modules','version':1,'extensions':entries},indent=2)+'\n')
    build_natune_catalog()
    # Only upgrade existing installations; these assets never install providers by default.
    migrations = ROOT.parents[1] / 'app/src/main/assets/module-migrations'
    migrations.mkdir(parents=True, exist_ok=True)
    for name in ('tidal', 'qobuz', 'archivepool'):
        (migrations / f'{name}.arch').write_bytes((dist / f'{name}.arch').read_bytes())


if __name__ == '__main__':
    main()
