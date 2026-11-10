#!/usr/bin/env python3
"""Reuse retained subject harnesses in fresh directories, without rewriting their evidence."""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

here = Path(__file__).resolve().parent
root = here.parent.parent
mode = sys.argv[1]
assert mode in ('petclinic-before', 'petclinic-after', 'asm12', 'asm13')
work = here / '.work' / mode
raw = here / 'raw' / 'implementation' / mode
work.mkdir(exist_ok=False)
raw.mkdir(exist_ok=False)
# Reuse only downloaded third-party dependencies from this task's initial run.
# STP artifacts always resolve from the explicit per-run published repository.
cache = here / '.work/petclinic-before/m2'
if mode != 'petclinic-before' and cache.is_dir():
    def omit_stp(directory, names):
        return ['smart-test-picker'] if Path(directory).as_posix().endswith('/com/sap/oss') else []
    shutil.copytree(cache, work / 'm2', ignore=omit_stp)
env = os.environ.copy()
env['PATH'] = '/Users/D061177/Programs/apache-maven-3.9.15/bin:' + env['PATH']
env['PERF_RAW'] = str(raw)
gson = Path.home() / '.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar'
asm = Path.home() / '.m2/repository/org/ow2/asm/asm/9.8/asm-9.8.jar'

# Snapshot only current published STP coordinates, not years of timestamped builds.
# The before repository differs only in the shaded agent binary, whose provenance
# is the retained Step 0 hash. Never overwrite the developer's Maven repository.
source = root / 'build/functional-test-maven-repository'
repository = work / 'published'
for metadata in source.glob('**/0.3.0-SNAPSHOT/maven-metadata.xml'):
    dest = repository / metadata.parent.relative_to(source)
    dest.mkdir(parents=True)
    shutil.copy2(metadata, dest / metadata.name)
    for item in ET.parse(metadata).findall('.//snapshotVersion'):
        version = item.findtext('value')
        extension = item.findtext('extension')
        classifier = item.findtext('classifier')
        artifact = metadata.parent.parent.name
        name = f'{artifact}-{version}' + (f'-{classifier}' if classifier else '') + f'.{extension}'
        content = (metadata.parent / name).read_bytes()
        if mode == 'petclinic-before' and artifact == 'stp-agent' and extension == 'jar':
            content = (here / '.work/agent-before.jar').read_bytes()
            assert hashlib.sha256(content).hexdigest() == 'dfa69a8afe2689b13e8ccecd9ee26817f252aa354485e4ffa37279e7ded981cc'
        (dest / name).write_bytes(content)
    for file in list(dest.iterdir()):
        for algorithm in ('md5', 'sha1', 'sha256', 'sha512'):
            (dest / (file.name + '.' + algorithm)).write_text(hashlib.new(algorithm, file.read_bytes()).hexdigest())
env['PERF_REPOSITORY'] = repository.as_uri()

if mode.startswith('petclinic'):
    source_script = root / 'verification/stp-petclinic-parity-01/run-maven.sh'
    text = source_script.read_text().replace(
        'raw="$root/verification/stp-petclinic-parity-01/raw/maven"', 'raw="$PERF_RAW"')
    # Time only the mapping command, as in the benchmark's measured-loop distinction.
    text = text.replace('mvn "${common[@]}" "${args[@]}" > "$raw/mapping.log" 2>&1',
        '/usr/bin/time -p -o "$raw/mapping-time.txt" mvn "${common[@]}" "${args[@]}" > "$raw/mapping.log" 2>&1')
    env.update(PARITY_WORK=str(work), PARITY_SUBJECT=str(here / '.work/petclinic-source'),
               PARITY_M2=str(work / 'm2'), PARITY_GSON=str(gson))
    args = []
else:
    number = '12' if mode == 'asm12' else '13'
    source_script = root / f'verification/maven-asm-{number}/run.sh'
    text = source_script.read_text().replace('"$evidence/raw/$mode"', '"$PERF_RAW"').replace(
        '"$evidence/raw/$label"', '"$PERF_RAW"')
    env.update({f'ASM{number}_WORK': str(work), f'ASM{number}_M2': str(work / 'm2'),
                f'ASM{number}_ASM': str(asm), f'ASM{number}_GSON': str(gson)})
    # Observe the new allocation-free entry, not the generic event slow path.
    # The fixture, gates, owner introspection and diagnostic assertions are unchanged.
    observer_name = 'ContextObserver.java' if mode == 'asm12' else 'BoundaryObserver.java'
    original_observer = root / f'verification/maven-asm-{number}' / observer_name
    observer = work / observer_name
    observer_text = original_observer.read_text().replace('"record"', '"recordMethod"')
    if mode == 'asm13':
        observer_text = observer_text.replace('event + " active="',
            'event.getClass().getMethod("method").invoke(event) + " active="')
    observer.write_text(observer_text)
    text = text.replace(f'"$evidence/{observer_name}"', f'"{observer}"')
    shutil.copy2(observer, raw / observer_name)
    args = ['asm-observed' if mode == 'asm12' else 'asm-parallel']
text = text.replace('export STP_ASM10_REPO="file://$root/build/functional-test-maven-repository"',
                    'export STP_ASM10_REPO="$PERF_REPOSITORY"')
script = work / 'run.sh'
script.write_text(text)
shutil.copy2(script, raw / 'run.sh')
with (raw / 'harness.log').open('w') as log:
    result = subprocess.run(['bash', str(script), *args], cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT)
print(f'{mode}: harness exit {result.returncode}; {raw}', flush=True)
if result.returncode == 0 and mode.startswith('petclinic'):
    digest = hashlib.sha256((raw / 'stp/coverage-fragment-v3.json').read_bytes()).hexdigest()
    (raw / 'fragment-sha256.txt').write_text(digest + '\n')
    print('Fragment SHA-256: ' + digest, flush=True)
    assert digest == '0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9', 'STOP: required fragment hash differs'
sys.exit(result.returncode)
