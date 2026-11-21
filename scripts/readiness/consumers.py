# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Run Java 17 consumers against a file repository and Central, with all writable state under --work."""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.sax.saxutils as xml

GROUP = 'com.sap.oss.smart-test-picker'


def run(command, cwd, env, log):
    with log.open('w') as output:
        process = subprocess.run([str(part) for part in command], cwd=cwd, env=env,
                                 stdout=output, stderr=subprocess.STDOUT)
    print(f'{log.name}: exit={process.returncode}', flush=True)
    return process.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--work', type=Path, required=True)
    args = parser.parse_args()
    args.work = args.work.resolve()
    args.work.mkdir(parents=True, exist_ok=True)
    required = ['STP_READINESS_JAVA17', 'STP_READINESS_GRADLE', 'STP_READINESS_MAVEN']
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        print('Set explicit tool paths: ' + ', '.join(missing))
        return 1
    home = Path(os.environ['STP_READINESS_JAVA17'])
    env = os.environ.copy()
    env.update(JAVA_HOME=str(home), GRADLE_USER_HOME=str(args.work / 'gradle-user-home'),
               TMPDIR=str(args.work / 'tmp'), MAVEN_OPTS='-Djava.io.tmpdir=' + str(args.work / 'tmp'))
    env.pop('JAVA_TOOL_OPTIONS', None)
    env.pop('_JAVA_OPTIONS', None)
    for name in ('tmp', 'gradle', 'maven'):
        (args.work / name).mkdir(exist_ok=True)
    version = subprocess.run([str(home / 'bin/java'), '-version'], capture_output=True, text=True, env=env)
    (args.work / 'java-version.log').write_text(version.stdout + version.stderr)
    if version.returncode or 'version "17.' not in version.stderr:
        print('The consumer JVM must be JDK 17; see java-version.log')
        return 1
    repository = args.repository.resolve().as_uri()
    gradle = args.work / 'gradle'
    (gradle / 'settings.gradle').write_text(f'''pluginManagement {{
    repositories {{
        exclusiveContent {{ forRepository {{ maven {{ url = uri('{repository}') }} }}
            filter {{ includeGroup('{GROUP}') }} }}
        mavenCentral()
    }}
}}
rootProject.name = 'readiness-consumer'
''')
    (gradle / 'build.gradle').write_text(f'''plugins {{
    id 'java'
    id '{GROUP}' version '{args.version}'
}}
repositories {{
    exclusiveContent {{ forRepository {{ maven {{ url = uri('{repository}') }} }}
        filter {{ includeGroup('{GROUP}') }} }}
    mavenCentral()
}}
configurations {{ readinessArtifacts }}
dependencies {{
    ['smart-test-picker', 'smart-test-picker-core', 'smart-test-picker-common',
     'smart-test-picker-maven', 'stp-agent', 'smart-test-picker-cli'].each {{ artifact ->
        readinessArtifacts '{GROUP}:' + artifact + ':{args.version}'
    }}
}}
test {{ useJUnitPlatform() }}
tasks.register('verifyConsumer') {{
    doLast {{
        assert JavaVersion.current() == JavaVersion.VERSION_17
        assert plugins.hasPlugin('{GROUP}')
        def artifacts = configurations.readinessArtifacts.resolvedConfiguration.resolvedArtifacts
        def ours = artifacts.findAll {{ it.moduleVersion.id.group == '{GROUP}' }}
        assert ours.collect {{ it.name }}.toSet() == ['smart-test-picker', 'smart-test-picker-core',
            'smart-test-picker-common', 'smart-test-picker-maven', 'stp-agent', 'smart-test-picker-cli'].toSet()
        ours.each {{ println('RESOLVED ' + it.moduleVersion.id + ' ' + it.file.length()) }}
        println('JAVA_17_PLUGIN_APPLIED')
    }}
}}
''')
    gradle_result = run([os.environ['STP_READINESS_GRADLE'], '--no-daemon', '--console=plain',
                         '-Djava.io.tmpdir=' + str(args.work / 'tmp'), 'verifyConsumer'],
                        gradle, env, args.work / 'gradle-consumer.log')
    maven = args.work / 'maven'
    (maven / 'pom.xml').write_text(f'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion><groupId>example.readiness</groupId><artifactId>consumer</artifactId><version>1</version>
<repositories><repository><id>stp-local</id><url>{xml.escape(repository)}</url></repository></repositories>
<pluginRepositories><pluginRepository><id>stp-local</id><url>{xml.escape(repository)}</url></pluginRepository></pluginRepositories>
</project>
''')
    settings = args.work / 'settings.xml'
    settings.write_text('''<settings><mirrors><mirror><id>central-only</id><mirrorOf>external:*</mirrorOf>
<url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>''')
    global_settings = args.work / 'global-settings.xml'
    global_settings.write_text('<settings/>')
    command = [os.environ['STP_READINESS_MAVEN'], '--batch-mode', '--no-transfer-progress',
               '--settings', settings, '--global-settings', global_settings,
               '-Dmaven.repo.local=' + str(args.work / 'maven-repository')]
    maven_result = run(command + [f'{GROUP}:smart-test-picker-maven:{args.version}:help', '-Ddetail=true'],
                       maven, env, args.work / 'maven-consumer.log')
    cli_result = run(command + ['org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy',
                                f'-Dartifact={GROUP}:smart-test-picker-cli:{args.version}',
                                '-DoutputDirectory=' + str(args.work / 'cli')],
                     maven, env, args.work / 'cli-resolution.log')
    if not cli_result:
        cli_result = run([home / 'bin/java', '-Djava.io.tmpdir=' + str(args.work / 'tmp'), '-jar',
                          args.work / 'cli' / f'smart-test-picker-cli-{args.version}.jar', '--help'],
                         args.work, env, args.work / 'cli-help.log')
    results = {'jdk': version.stderr.strip(), 'version': args.version,
               'gradleExit': gradle_result, 'mavenExit': maven_result, 'cliExit': cli_result}
    (args.work / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    return int(any((gradle_result, maven_result, cli_result)))


if __name__ == '__main__':
    sys.exit(main())
