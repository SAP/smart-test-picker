#!/usr/bin/env python3
"""Retain fresh suite output and bind its agent to the three fresh local gates."""
from pathlib import Path
import json
import shutil
from zipfile import ZipFile
import hashlib

here = Path(__file__).resolve().parent
root = here.parent.parent
original = root / 'verification/stp-integrity-01/finish.py'
source = original.read_text().replace("here = Path(__file__).resolve().parent", f"here = Path({str(here)!r})")
source = source.replace('verification/stp-perf-01/raw/implementation/suite-counts.json',
                        'verification/stp-integrity-01/raw/suite-counts.json')
exec(compile(source, str(original), 'exec'))

cases = {}
for mode in ('failed', 'unfinished', 'missing-result', 'unsupported', 'unowned-error'):
    origin = root / 'smart-test-picker-maven/build/stp-unmapped-01' / mode
    destination = here / 'raw/regressions' / mode
    destination.mkdir(parents=True, exist_ok=True)
    for file in origin.iterdir():
        if file.is_file(): shutil.copy2(file, destination / file.name)
    fragment = json.loads((destination / 'module-fragment.json').read_text())
    cases[mode] = dict(mapped=len(fragment['tests']), unmapped=fragment['unmapped'],
                       complete=fragment['collection']['completed'],
                       selected=(destination / 'selection.txt').read_text().split(),
                       assignmentAbsorption=fragment.get('assignmentAbsorption'))
(here / 'raw/regression-results.json').write_text(json.dumps(cases, indent=2) + '\n')
print(json.dumps(cases, indent=2))

tested = root / 'smart-test-picker-maven/build/libs/smart-test-picker-maven-0.3.0-SNAPSHOT.jar'
provenance = {'testedMavenPluginSha256': hashlib.sha256(tested.read_bytes()).hexdigest(), 'gates': {}}
for mode in ('petclinic-after', 'asm12', 'asm13'):
    jars = list((here / '.work' / mode / 'published/com/sap/oss/smart-test-picker/smart-test-picker-maven').rglob('*.jar'))
    assert len(jars) == 1, jars
    with ZipFile(tested) as a, ZipFile(jars[0]) as b:
        names = set(a.namelist()) | set(b.namelist())
        different = [name for name in names if name not in a.namelist() or name not in b.namelist()
                     or a.read(name) != b.read(name)]
    assert not different, (mode, different)
    provenance['gates'][mode] = {'sha256': hashlib.sha256(jars[0].read_bytes()).hexdigest(),
                               'entryCount': len(names), 'differentEntryContents': different}
(here / 'raw/maven-plugin-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
