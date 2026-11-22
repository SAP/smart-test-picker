# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Small regression tests for the readiness checker itself, independent of product changes."""

import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('verify', Path(__file__).with_name('verify.py'))
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


class CheckTests(unittest.TestCase):
    def test_version_scan_does_not_confuse_coordinate_type_or_goal_with_version(self):
        self.assertEqual([], verify.documented_versions(
            'com.sap.oss.smart-test-picker:smart-test-picker-maven:help'))
        self.assertEqual(['0.3.0-SNAPSHOT'], verify.documented_versions(
            'com.sap.oss.smart-test-picker:stp-agent:jar:0.3.0-SNAPSHOT'))
        self.assertEqual(['0.1.0'], verify.documented_versions(
            '<groupId>com.sap.oss.smart-test-picker</groupId>\n'
            '<artifactId>stp-agent</artifactId><version>0.1.0</version>'))

    def test_tagged_remote_evidence_is_not_a_local_dependency(self):
        self.assertEqual([], verify.local_evidence_dependencies(
            'https://github.com/SAP/smart-test-picker/blob/example-tag/verification/example/REPORT.md'))
        self.assertEqual(['verification/example/run.sh'], verify.local_evidence_dependencies(
            'Run `verification/example/run.sh` locally.'))
        self.assertEqual(['../verification/example/run.sh'], verify.local_evidence_dependencies(
            'Run `../verification/example/run.sh` locally.'))

    def test_reference_requires_four_fields_and_retains_escaped_pipes(self):
        rows = verify.reference_rows('| `maven:goal:groups` | none | Tags fast \\| slow | 3 |\n'
                                     '| `cli:join-fragments` | no detail |\n')
        self.assertEqual(('none', 'Tags fast \\| slow', '3'), rows['maven:goal:groups'])
        self.assertNotIn('cli:join-fragments', rows)
        encoded = verify.reference_rows('| `cli:sample:--values` | new ArrayList&lt;&gt;() | Repeated values | 3 |')
        self.assertEqual('new ArrayList<>()', encoded['cli:sample:--values'][0])

    def test_snapshot_staging_uses_timestamped_name(self):
        directory = Path('build/readiness/checker-tests')
        directory.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=directory) as temp:
            path = Path(temp)
            (path / 'maven-metadata.xml').write_text('<metadata><versioning><snapshot>'
                '<timestamp>20261121.120000</timestamp><buildNumber>1</buildNumber>'
                '</snapshot></versioning></metadata>')
            self.assertEqual('sample-1.0-20261121.120000-1', verify.publication_stem(path, 'sample', '1.0-SNAPSHOT'))
            self.assertEqual('sample-1.0', verify.publication_stem(path, 'sample', '1.0'))

    def test_failure_is_not_lost_after_a_passing_check(self):
        checks = verify.Checks()
        checks.check(False, 'A1', 'missing publication')
        checks.check(True, 'A2', 'metadata present')
        self.assertEqual([False, True], [row['passed'] for row in checks.rows])


if __name__ == '__main__':
    unittest.main()
