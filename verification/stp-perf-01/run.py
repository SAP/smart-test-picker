#!/usr/bin/env python3
"""Run isolated pre-change JVMs; retain all evidence and reject incomplete collection."""
import hashlib
import json
import os
from pathlib import Path
import shlex
import statistics
import subprocess
import tempfile
import time

here = Path(__file__).resolve().parent
repo = here.parent.parent
phase = os.environ.get('BENCH_PHASE', 'before')
assert phase in ('before', 'after')
raw = here / 'raw' / phase
raw.mkdir(parents=True, exist_ok=False)
work = Path(tempfile.mkdtemp(prefix='stp-perf-01.'))
agent = Path(os.environ.get('BENCH_AGENT', str(repo / 'stp-agent/build/libs/stp-agent.jar')))
m2 = Path(os.environ.get('BENCH_M2', str(Path.home() / '.m2/repository')))
coordinates = [
    ('org/junit/jupiter', 'junit-jupiter-api', '6.0.3'),
    ('org/junit/jupiter', 'junit-jupiter-engine', '6.0.3'),
    ('org/junit/platform', 'junit-platform-commons', '6.0.3'),
    ('org/junit/platform', 'junit-platform-engine', '6.0.3'),
    ('org/junit/platform', 'junit-platform-launcher', '6.0.3'),
    ('org/opentest4j', 'opentest4j', '1.3.0'),
    ('org/apiguardian', 'apiguardian-api', '1.1.2'),
]
jars = [m2 / group / artifact / version / f'{artifact}-{version}.jar'
        for group, artifact, version in coordinates]
for path in jars + [agent]:
    assert path.is_file(), path
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
if phase == 'before':
    assert sha(agent) == 'dfa69a8afe2689b13e8ccecd9ee26817f252aa354485e4ffa37279e7ded981cc'
classpath = os.pathsep.join(str(p) for p in jars)
classes = work / 'classes'
classes.mkdir()
commands = [['git', 'rev-parse', 'HEAD'], ['java', '-version'], ['uname', '-a'],
            ['sysctl', '-n', 'hw.model', 'hw.ncpu', 'hw.memsize']]
with (raw / 'environment.txt').open('w') as out:
    for command in commands:
        out.write(shlex.join(command) + '\n')
        out.flush()
        subprocess.run(command, cwd=repo, stdout=out, stderr=subprocess.STDOUT, check=True)
    for path in jars + [agent]:
        out.write(f'{sha(path)}  {path}\n')
compile_command = ['javac', '--release', '17', '-cp', classpath, '-d', str(classes)] + [
    str(p) for p in sorted((here / 'fixture').rglob('*.java'))]
(raw / 'compile-command.txt').write_text(shlex.join(compile_command) + '\n')
with (raw / 'compile.log').open('w') as out:
    subprocess.run(compile_command, stdout=out, stderr=subprocess.STDOUT, check=True)
samples = []
configurations = [('hot', n, 1) for n in [1_000_000, 10_000_000, 100_000_000]] + [
    ('contention', 10_000_000, n) for n in [1, 2, 4, 8, 16]]
for kind, calls, threads in configurations:
    for repetition in range(1, 4):
        checksums = []
        for mode in (['control', 'agent'] if repetition % 2 else ['agent', 'control']):
            name = f'{kind}-{calls}-{threads}-r{repetition}-{mode}'
            output = raw / name
            output.mkdir()
            command = ['java', '-Xms256m', '-Xmx256m', '-XX:+UseG1GC',
                       f'-Dbench.calls={calls}', f'-Dbench.threads={threads}',
                       f'-Dbench.contention={str(kind == "contention").lower()}',
                       f'-Dbench.agent={str(mode == "agent").lower()}']
            if mode == 'agent':
                command.append(f'-javaagent:{agent}=output={output}/diagnostic.json;'
                               f'fragmentOutput={output}/fragment.json;schemaVersion=3;'
                               f'revision=498ada8831db49ea654af136a72329706a3cb0a0;'
                               f'shardId=bench;executionTarget=maven:.;runId={name};includes=perf.subject.')
            command += ['-cp', str(classes) + os.pathsep + classpath, 'perf.Run']
            (output / 'command.txt').write_text(shlex.join(command) + '\n')
            started = time.monotonic()
            with (output / 'console.log').open('w') as log:
                completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=600)
            process_seconds = time.monotonic() - started
            (output / 'exit-code.txt').write_text(str(completed.returncode) + '\n')
            assert completed.returncode == 0, name
            lines = (output / 'console.log').read_text().splitlines()
            measurements = [json.loads(line[6:]) for line in lines if line.startswith('BENCH ')]
            assert len(measurements) == 1, name
            measurement = measurements[0]
            checksums.append(measurement['checksum'])
            if mode == 'agent':
                diagnostic = json.loads((output / 'diagnostic.json').read_text())
                assert diagnostic['agentErrors'] == [], name
                assert diagnostic['metrics']['rawMethodHits'] == calls + 1_000_000, name
                assert diagnostic['metrics']['uniqueMethodHits'] == 1, name
                assert diagnostic['metrics']['transformationErrors'] == 0, name
                assert diagnostic['runtimeEvents']['unattributedEvents'] == [], name
                tests = diagnostic['runtimeEvents']['tests']
                assert len(tests) == 1 and tests[0]['metrics']['rawMethodHits'] == calls + 1_000_000, name
                assert tests[0]['unattributedEvents'] == [], name
            measurement.update(kind=kind, mode=mode, repetition=repetition, processSeconds=process_seconds)
            samples.append(measurement)
            (raw / 'samples.json').write_text(json.dumps(samples, indent=2) + '\n')
            print(f'{name}: loop={measurement["nanos"]/1e9:.6f}s process={process_seconds:.3f}s', flush=True)
        assert checksums[0] == checksums[1], (kind, calls, threads, checksums)
rows = []
for kind, calls, threads in configurations:
    row = dict(kind=kind, calls=calls, threads=threads)
    for mode in ['control', 'agent']:
        values = [s['nanos']/1e9 for s in samples if (s['kind'], s['calls'], s['threads'], s['mode'])
                  == (kind, calls, threads, mode)]
        row[mode] = dict(medianSeconds=statistics.median(values), minSeconds=min(values), maxSeconds=max(values),
                         medianNsPerCall=statistics.median(values)*1e9/calls)
    row['ratio'] = row['agent']['medianSeconds']/row['control']['medianSeconds']
    rows.append(row)
(raw / 'summary.json').write_text(json.dumps(rows, indent=2) + '\n')
print(json.dumps(rows, indent=2), flush=True)
