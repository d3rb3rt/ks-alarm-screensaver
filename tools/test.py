#!/usr/bin/env python3
"""Compile the plugin against the SDK and the Android platform, and check the manifest."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from android_sdk import android_platform

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'kiosk-satellite-plugin.json').read_text())
entry = root / 'src' / (manifest['entryClass'].replace('.', '/') + '.java')
assert entry.is_file(), f'entry class source missing: {entry}'
keys = [s['key'] for s in manifest['settings']]
assert len(keys) == len(set(keys)), 'duplicate setting keys'
for capability in manifest['capabilities']:
    assert capability in {'overlay', 'host.read', 'host.control', 'ha', 'entities'}, capability

java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name
sdk_root = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
platform = str(android_platform(sdk_root))
sources = [*sorted((root / 'sdk/src').rglob('*.java')), *sorted((root / 'src').rglob('*.java'))]
with tempfile.TemporaryDirectory(prefix='snooze-screensaver-test-') as directory:
    subprocess.run([tool('javac'), '--release', '8', '-cp', platform, '-d', directory, *map(str, sources)], check=True)
print('ok')
