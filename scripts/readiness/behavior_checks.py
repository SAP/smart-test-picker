# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Negative checks against public Gradle tasks, using only workspace-local scratch directories."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('guards', 'versions'))
    parser.add_argument('--root', type=Path, default=Path.cwd())
    args = parser.parse_args()
    root = args.root.resolve()
    work = root / 'build/readiness/behavior' / args.mode
    work.mkdir(parents=True, exist_ok=True)
    executable = os.environ.get('STP_READINESS_GRADLE')
    if not executable:
        print('Set STP_READINESS_GRADLE to the local Gradle executable')
        return 1
    env = os.environ.copy()
    env.pop('STP_SIGNING_KEY', None)
    env.pop('STP_SIGNING_PASSWORD', None)
    env['TMPDIR'] = str(work)
    env['GRADLE_USER_HOME'] = str(root / 'build/readiness/gradle-user-home')
    outcomes = []

    def run(directory, name, arguments, expected):
        command = [executable, '--no-daemon', '--console=plain', '-Djava.io.tmpdir=' + str(work), *arguments]
        result = subprocess.run(command, cwd=directory, env=env, capture_output=True, text=True)
        text = result.stdout + result.stderr
        (work / (name + '.log')).write_text(text)
        passed = result.returncode != 0 and all(item in text for item in expected)
        outcomes.append(dict(name=name, passed=passed, exit=result.returncode, expected=expected))
        print(('PASS ' if passed else 'FAIL ') + name, flush=True)

    if args.mode == 'guards':
        run(root, 'snapshot-refused', ['centralBundle', '-PstpVersion=0.0.0-readiness-SNAPSHOT'], ['non-SNAPSHOT'])
        run(root, 'missing-key-refused', ['centralBundle', '-PstpVersion=0.0.0-readiness'], ['STP_SIGNING_KEY'])
    else:
        # Each mutation starts from identical tracked files, not the user's working tree or untracked data.
        files = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
        targets = ['README.md', 'smart-test-picker/README.md', 'smart-test-picker-maven/README.md',
                   'smart-test-picker-core/README.md', 'docs/artifact-versioning.md']
        for index, target in enumerate(targets):
            directory = work / ('mutation-' + str(index))
            directory.mkdir(exist_ok=True)
            for name in filter(None, files):
                destination = directory / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(root / name, destination)
            document = directory / target
            with document.open('a') as output:
                output.write('\nInstall check: `com.sap.oss.smart-test-picker:smart-test-picker-core:0.0.0-stale-doc`\n')
            # A Git index gives the normal tracked-document verifier the same input boundaries.
            subprocess.run(['git', 'init', '-q', str(directory)], check=True, env=env)
            subprocess.run(['git', '-C', str(directory), 'add', '.'], check=True, env=env)
            run(directory, 'stale-document-' + str(index), ['verifyStpVersionConsistency'], [target, '0.0.0-stale-doc'])
    (work / 'results.json').write_text(json.dumps(outcomes, indent=2) + '\n')
    return int(any(not row['passed'] for row in outcomes))


if __name__ == '__main__':
    sys.exit(main())
