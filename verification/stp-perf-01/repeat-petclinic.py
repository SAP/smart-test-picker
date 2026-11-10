#!/usr/bin/env python3
"""Paired mapping timings with dependencies warm, fresh test JVMs and no competing builds."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import time

here = Path(__file__).resolve().parent
rows = []
for mode in ('petclinic-before', 'petclinic-after'):
    work = here / '.work' / mode
    raw = here / 'raw/implementation' / mode
    dest = raw / 'repeat'
    dest.mkdir(exist_ok=False)
    env = os.environ.copy()
    env['PATH'] = '/Users/D061177/Programs/apache-maven-3.9.15/bin:' + env['PATH']
    env['MAVEN_OPTS'] = '-Xmx768m'
    env['STP_ASM10_REPO'] = (work / 'published').as_uri()
    env.pop('STP_MAPPING_COLLECTOR', None)
    command = shlex.split((raw / 'command.txt').read_text())
    started = time.monotonic()
    with (dest / 'mapping.log').open('w') as log:
        result = subprocess.run(command, cwd=work / 'maven', env=env, stdout=log, stderr=subprocess.STDOUT)
    seconds = time.monotonic() - started
    assert result.returncode == 0, mode
    for filename in ('coverage-fragment-v3.json', 'asm-agent-diagnostic.json', 'execution-evidence-v2.json'):
        shutil.copy2(work / 'maven/target/stp' / filename, dest / filename)
    digest = hashlib.sha256((dest / 'coverage-fragment-v3.json').read_bytes()).hexdigest()
    assert digest == '0ecdbed9d086835971a9c69e3a49b6977ca24c42058ce2bf3618d8fc1820b8b9', 'STOP: required hash differs'
    row = dict(mode=mode, seconds=seconds, exitCode=result.returncode, fragmentSha256=digest)
    rows.append(row)
    (dest / 'result.json').write_text(json.dumps(row, indent=2) + '\n')
    print(json.dumps(row), flush=True)
(here / 'raw/implementation/petclinic-timing.json').write_text(json.dumps(rows, indent=2) + '\n')
