"""Source hygiene checks only. This does NOT compile Kotlin or validate dependency resolution."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
files = list(root.glob('app/src/main/**/*.xml'))
for path in files:
    ET.parse(path)
assert (root / 'settings.gradle.kts').exists()
assert (root / 'app/build.gradle.kts').exists()
manifest = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
android = '{http://schemas.android.com/apk/res/android}'
app = manifest.find('application')
assert app.attrib[android + 'allowBackup'] == 'false'
assert app.attrib[android + 'usesCleartextTraffic'] == 'false'
source = '\n'.join(p.read_text() for p in root.glob('app/src/main/java/**/*.kt'))
for forbidden in ('TrustAll', 'setDebug(true)', 'Log.d(', 'Log.e(', 'mail.smtp.ssl.trust'):
    assert forbidden not in source, forbidden
assert 'starttls.required' in source
assert 'ssl.checkserveridentity' in source
assert 'UNCERTAIN' in source
print(f'PASS: {len(files)} XML files parse; source hygiene checks passed.')
print('NOT RUN: Gradle dependency resolution, Kotlin compilation, JVM tests, device tests, SMTP delivery.')
