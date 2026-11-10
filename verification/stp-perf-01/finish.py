#!/usr/bin/env python3
"""Retain full-suite results and verify measured/rebuilt agent content equivalence."""
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
from zipfile import ZipFile

here = Path(__file__).resolve().parent
root = here.parent.parent
raw = here / 'raw/implementation'
log = (raw / 'full-suites.log').read_text()
assert 'BUILD SUCCESSFUL' in log and 'BUILD FAILED' not in log, 'Full rerun has not passed'
modules = ('smart-test-picker-common', 'smart-test-picker-core', 'smart-test-picker-maven',
           'smart-test-picker', 'stp-runtime', 'stp-junit-adapter', 'stp-agent', 'smart-test-picker-cli')
counts = {}
baseline = json.loads((root / 'verification/stp-gradle-setup-01/raw/suite-counts.json').read_text())
for module in modules:
    assert f'> Task :{module}:test\n' in log, f'{module} test was not freshly executed'
    folder = raw / 'test-results' / module
    folder.mkdir(parents=True, exist_ok=True)
    values = dict(tests=0, failures=0, errors=0, skipped=0)
    files = sorted((root / module / 'build/test-results/test').glob('TEST-*.xml'))
    assert files, module
    for path in files:
        suite = ET.parse(path).getroot()
        for key in values:
            values[key] += int(suite.attrib.get(key, 0))
        shutil.copy2(path, folder / path.name)
    values['passed'] = values['tests'] - values['failures'] - values['errors'] - values['skipped']
    assert values['failures'] == values['errors'] == 0, (module, values)
    counts[module] = values
    assert values['tests'] >= baseline[module]['tests'], (module, 'test count dropped')
    assert values['passed'] >= baseline[module]['passed'], (module, 'passing count dropped')
    assert values['skipped'] == baseline[module]['skipped'], (module, 'skip count changed')
assert sum(row['tests'] for row in counts.values()) >= 721
(raw / 'suite-counts.json').write_text(json.dumps(counts, indent=2) + '\n')
table = ['| Module | Tests | Passed | Skipped | Failures/errors |', '|---|---:|---:|---:|---:|']
for module, row in counts.items():
    table.append(f'| {module} | {row["tests"]} | {row["passed"]} | {row["skipped"]} | 0 |')
(raw / 'suite-counts.md').write_text('\n'.join(table) + '\n')
print('\n'.join(table))

old = next((here / '.work/petclinic-after/published/com/sap/oss/smart-test-picker/stp-agent').rglob('*.jar'))
new = root / 'stp-agent/build/libs/stp-agent.jar'
with ZipFile(old) as a, ZipFile(new) as b:
    names = set(a.namelist()) | set(b.namelist())
    differences = [name for name in sorted(names)
                   if name not in a.namelist() or name not in b.namelist() or a.read(name) != b.read(name)]
    provenance = dict(measuredAgentSha256=hashlib.sha256(old.read_bytes()).hexdigest(),
                      rebuiltAgentSha256=hashlib.sha256(new.read_bytes()).hexdigest(),
                      entryCount=len(names), differingEntryContents=differences)
    assert differences == [], provenance
(raw / 'agent-provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print(json.dumps(provenance, indent=2))
