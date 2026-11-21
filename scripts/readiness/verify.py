# SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
# SPDX-License-Identifier: Apache-2.0
"""Local artifact/documentation checks. No upload or registry authentication code."""

import argparse
import hashlib
import html
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tempfile
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile

GROUP = 'com.sap.oss.smart-test-picker'
MODULES = ['smart-test-picker', 'smart-test-picker-core', 'smart-test-picker-common',
           'smart-test-picker-maven', 'stp-agent', 'smart-test-picker-cli']
ARTIFACTS = set(MODULES + [GROUP + '.gradle.plugin'])
HASHES = ('md5', 'sha1', 'sha256', 'sha512')
NS = {'p': 'http://maven.apache.org/POM/4.0.0'}


class Checks:
    def __init__(self):
        self.rows = []

    def check(self, passed, requirement, detail):
        self.rows.append({'requirement': requirement, 'passed': bool(passed), 'detail': detail})
        print(('PASS' if passed else 'FAIL') + ' ' + requirement + ' ' + detail, flush=True)

    def finish(self, output):
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(self.rows, indent=2) + '\n')
        failures = sum(not row['passed'] for row in self.rows)
        print(f'{len(self.rows)} checks, {failures} failures')
        return 1 if failures else 0


def git(root, *args):
    return subprocess.run(['git', *args], cwd=root, text=True, capture_output=True)


def tracked(root):
    result = git(root, 'ls-files', '-z')
    result.check_returncode()
    return [Path(p) for p in result.stdout.split('\0') if p]


def source_surface(root, work):
    java = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else 'java'
    result = subprocess.run([str(java), '-Djava.io.tmpdir=' + str(work),
                             str(root / 'scripts/readiness/SourceSurface.java'), str(root)],
                            text=True, capture_output=True)
    if result.returncode:
        raise ValueError('Source surface parser failed: ' + result.stderr)
    surface = json.loads(result.stdout)
    for path in tracked(root):
        if str(path).startswith('smart-test-picker/src/main/java/'):
            text = (root / path).read_text()
            for name, suffix in re.findall(r'getTasks\(\)\.(?:register|create)\(\s*"([^"]+)"(\s*\+\s*\w+)?', text):
                if suffix:
                    name += '<' + suffix.split('+', 1)[1].strip() + '>'
                surface.append(dict(kind='gradle-task', id='gradle-task:' + name, file=str(path),
                                    default='not applicable', property='', doc='', readonly=''))
    # Generated help is a real published goal even though its source uses Maven doclet tags.
    descriptor = root / 'smart-test-picker-maven/build/mavenPlugin/descriptor/META-INF/maven/plugin.xml'
    if descriptor.is_file():
        for mojo in ET.parse(descriptor).findall('mojos/mojo'):
            goal = mojo.findtext('goal')
            if any(row['id'] == 'maven:' + goal for row in surface):
                continue
            surface.append(dict(kind='maven-goal', id='maven:' + goal, file=str(descriptor.relative_to(root)),
                                default='not applicable', property='', doc=mojo.findtext('description', ''), readonly=''))
            for parameter in mojo.findall('parameters/parameter'):
                name = parameter.findtext('name')
                configuration = mojo.find('configuration/' + name)
                value = configuration.get('default-value', '') if configuration is not None else ''
                surface.append(dict(kind='generated-maven-parameter', id='maven:' + goal + ':' + name,
                                    file=str(descriptor.relative_to(root)), default=value, property='',
                                    doc=parameter.findtext('description', ''), readonly=''))
    surface = list({row['id']: row for row in surface}.values())
    (work / 'source-surface.json').write_text(json.dumps(surface, indent=2) + '\n')
    return surface


def publication_stem(directory, artifact, version):
    metadata = directory / 'maven-metadata.xml'
    if version.endswith('-SNAPSHOT') and metadata.exists():
        snapshot = ET.parse(metadata).find('versioning/snapshot')
        if snapshot is not None:
            version = version.removesuffix('SNAPSHOT') + snapshot.findtext('timestamp') + '-' + snapshot.findtext('buildNumber')
    return artifact + '-' + version


def verify_artifacts(args, checks):
    model = json.loads(args.model.read_text())
    coords = [(p['group'], p['artifact']) for p in model['publications']]
    checks.check(set(coords) == {(GROUP, name) for name in ARTIFACTS}, 'A1', 'exact publication coordinates')
    checks.check(len(coords) == len(set(coords)), 'A1', 'no duplicate publication coordinates')
    checks.check(not model['version'].endswith('-SNAPSHOT'), 'A1', 'command-line non-SNAPSHOT test version')
    names, descriptions = [], []
    for artifact in sorted(ARTIFACTS):
        directory = args.repository / GROUP.replace('.', '/') / artifact / model['version']
        stem = publication_stem(directory, artifact, model['version'])
        pom = directory / (stem + '.pom')
        checks.check(pom.is_file(), 'A1', artifact + ' POM exists')
        if not pom.is_file():
            continue
        tree = ET.parse(pom).getroot()
        for field in ('name', 'description', 'url', 'licenses/license/name', 'licenses/license/url',
                      'developers/developer/name', 'scm/connection', 'scm/developerConnection', 'scm/url'):
            value = tree.findtext('/'.join('p:' + part for part in field.split('/')), namespaces=NS)
            checks.check(bool(value and value.strip()), 'A2', artifact + ' ' + field)
        names.append(tree.findtext('p:name', namespaces=NS))
        descriptions.append(tree.findtext('p:description', namespaces=NS))
        checks.check(tree.find('p:repositories', NS) is None and tree.find('p:pluginRepositories', NS) is None,
                     'A2', artifact + ' no embedded repositories')
        checks.check(tree.findtext('p:version', namespaces=NS) == model['version'], 'A2', artifact + ' version binding')
        if tree.findtext('p:packaging', default='jar', namespaces=NS) == 'pom':
            dependencies = tree.findall('p:dependencies/p:dependency', NS)
            checks.check(any(d.findtext('p:groupId', namespaces=NS) == GROUP
                             and d.findtext('p:artifactId', namespaces=NS) == 'smart-test-picker'
                             and d.findtext('p:version', namespaces=NS) == model['version'] for d in dependencies),
                         'A1', 'plugin marker resolves the plugin at the tested version')
            continue
        for suffix, content in (('.jar', '.class'), ('-sources.jar', '.java'), ('-javadoc.jar', '.html')):
            file = directory / (stem + suffix)
            checks.check(file.is_file(), 'A3', artifact + suffix + ' exists')
            if not file.is_file():
                continue
            with zipfile.ZipFile(file) as jar:
                checks.check(any(name.endswith(content) for name in jar.namelist()), 'A3', file.name + ' real content')
                if suffix == '.jar':
                    classes = [(name, int.from_bytes(jar.read(name)[6:8], 'big')) for name in jar.namelist()
                               if name.endswith('.class')]
                    too_new = [(name, version) for name, version in classes if version > 61]
                    checks.check(bool(classes) and not too_new, 'A7', file.name + ' all class files <= 61; '
                                 + f'max={max((v for _, v in classes), default=0)}; examples={too_new[:5]}')
        jar_path = directory / (stem + '.jar')
        if artifact in model['shaded'] and jar_path.exists():
            with zipfile.ZipFile(jar_path) as jar:
                entries = jar.namelist()
                for dep in model['shaded'][artifact]:
                    prefix = f'META-INF/third-party/{dep["group"]}/{dep["artifact"]}/'
                    checks.check(any(name.startswith(prefix) and 'license' in name.lower() for name in entries),
                                 'A5', artifact + ' license: ' + dep['group'] + ':' + dep['artifact'])
                checks.check('META-INF/third-party/NOTICE.txt' in entries, 'A5', artifact + ' attribution/notice index')
    # Marker and implementation may share a display name, but unrelated modules must not.
    checks.check(len(set(n for n in names if n)) >= len(MODULES), 'A2', 'module-specific POM names')
    checks.check(len(set(n for n in descriptions if n)) >= len(MODULES), 'A2', 'module-specific POM descriptions')


def verify_java17(args, checks, work):
    model = json.loads(args.model.read_text())
    source = work / 'NewerApiProbe.java'
    source.write_text('class NewerApiProbe { String first() { return java.util.List.of("value").getFirst(); } }\n')
    for config in model['compilers']:
        restricted = config['release'] == 17 or (config['release'] is None and config['compilerVersion'] == 17)
        checks.check(restricted, 'A7', config['module'] + ' Java 17 API boundary: ' + json.dumps(config))
        destination = work / config['module']
        destination.mkdir(exist_ok=True)
        command = [config['compiler'], '-d', str(destination)]
        if config['release'] is not None:
            command += ['--release', str(config['release'])]
        else:
            command += ['-source', config['source'], '-target', config['target']]
        command += config['arguments'] + [str(source)]
        result = subprocess.run(command, capture_output=True, text=True)
        (destination / 'api-probe.log').write_text(result.stdout + result.stderr)
        checks.check(result.returncode != 0 and 'getFirst' in result.stderr and 'cannot find symbol' in result.stderr,
                     'A7', config['module'] + ' rejects List.getFirst (Java 21 API), exit=' + str(result.returncode))


def reference_rows(markdown):
    rows = {}
    for line in markdown.splitlines():
        if not line.startswith('|'):
            continue
        cells = re.split(r'(?<!\\)\|', line.strip())[1:-1]
        if len(cells) != 4:
            continue
        key, default, meaning, schemas = [html.unescape(cell.strip().strip('`')) for cell in cells]
        if key.startswith(('maven:', 'cli:', 'gradle:', 'gradle-task:')):
            rows[key] = (default, meaning, schemas)
    return rows


def local_evidence_dependencies(text):
    # Tagged remote evidence links are supported; only local untracked paths are forbidden.
    tokens = [token.strip('"\'').rstrip('.,') for token in re.findall(r'[^\s`<>()]+', text)]
    return [token for token in tokens if token.startswith(('verification/', './verification/', '../verification/'))]


def documented_versions(text):
    """Find STP versions, not dependency versions or a Maven goal/type coordinate."""
    patterns = [r'com\.sap\.oss\.smart-test-picker:[\w.-]+:(?:jar:)?([0-9][\w.-]+)',
                r"(?:version|stpVersion)\s*[=:]?\s*['\"](0\.\d+\.\d+(?:-[\w.-]+)?)['\"]",
                r'smart-test-picker(?:-[\w-]+)?-(0\.\d+\.\d+(?:-SNAPSHOT)?)\.(?:jar|zip)',
                r'(?<![\w.])(0\.3\.0(?:-SNAPSHOT)?)(?![\w.-])',
                r'(?s)<groupId>com\.sap\.oss\.smart-test-picker</groupId>\s*<artifactId>[^<]+</artifactId>\s*<version>([0-9][^<]+)</version>']
    return sorted({match.group(1) for pattern in patterns for match in re.finditer(pattern, text)})


def verify_documented_versions(root, checks):
    version = re.search(r'(?m)^stpVersion=(.+)$', (root / 'gradle.properties').read_text()).group(1)
    for path in tracked(root):
        if path.suffix.lower() != '.md' or str(path).startswith(('docs/history/', 'verification/')):
            continue
        text = (root / path).read_text()
        if path.name == 'CHANGELOG.md':
            text = text.split('## [Unreleased]', 1)[-1].split('\n## [', 1)[0]
        for candidate in documented_versions(text):
            checks.check(candidate == version, 'A6', str(path) + ' documented STP version ' + candidate
                         + '; expected ' + version)


def verify_docs(args, checks, work):
    root = args.root
    paths = [p for p in tracked(root) if p.suffix.lower() == '.md']
    current = [p for p in paths if not str(p).startswith(('docs/history/', 'verification/'))]
    surface = source_surface(root, work)
    references = reference_rows('\n'.join((root / p).read_text() for p in current))
    exclusions = json.loads((root / 'scripts/readiness/reference-exclusions.json').read_text())
    indexed = {row['id']: row for row in surface}
    for key, reason in exclusions.items():
        row = indexed.get(key)
        checks.check(bool(reason.strip()) and row is not None and row['readonly'] == 'true', 'D2', 'explicit exclusion: ' + key + ': ' + reason)
    for row in surface:
        key = row['id']
        if row['kind'] != 'type' and key not in exclusions:
            reference = references.get(key)
            checks.check(reference is not None, 'D2', key + ' has a reference row (default, meaning, schemas)')
            if reference:
                default, meaning, schemas = reference
                checks.check(bool(default) and len(meaning) >= 12 and bool(re.search(r'(legacy|[123])', schemas, re.I)),
                             'D2', key + ' reference fields populated')
                if row['default']:
                    checks.check(row['default'] in default.replace('\\|', '|'), 'D2', key + ' annotation default matches reference')
                if row['property']:
                    checks.check(row['property'] in meaning, 'D2', key + ' user property is named')
        needs_doc = row['kind'] in ('type', 'gradle-property') or (row['kind'] == 'maven-parameter' and row['readonly'] != 'true')
        if needs_doc:
            checks.check(bool(row['doc'].strip()), 'D6', key + ' Javadoc exists')
            if row['kind'] != 'type':
                checks.check(bool(re.search(r'\bdefault\b', row['doc'], re.I)), 'D6', key + ' Javadoc states default')
    readme = (root / 'README.md').read_text()
    for phrase in ('schema-v3', 'ASM', 'JACOCO', 'inventory', 'assignment', 'mapping', 'aggregation', 'join', 'selection',
                   'Java 17', '8.0', '10.0', '3.9', '8.14', '9.5.1', '9.7.1', '9.9.1', '3.9.15', '3.2.5', '3.5.6', '3.6.1'):
        checks.check(phrase.lower() in readme.lower(), 'D1', 'root README covers ' + phrase)
    checks.check(bool(re.search(r'(?im)^#+ .*legacy', readme)), 'D1', 'legacy workflow explicitly separated')
    for tool in ('Gradle', 'Maven', 'CLI'):
        checks.check(bool(re.search(r'(?im)^#+ .*' + tool, readme)), 'D1', tool + ' quick start section')
    for path in current:
        text = (root / path).read_text()
        if path.parts[0] == 'docs':
            historical = re.match(r'(task\d|ei-\d|mm-decision-|asm-research-)', path.name)
            checks.check(not historical, 'D3', str(path) + ' historical record is outside current docs')
        if path.name != 'CHANGELOG.md':
            checks.check(not local_evidence_dependencies(text), 'D3', str(path) + ' no untracked evidence dependency')
    history = root / 'docs/history/README.md'
    checks.check(history.is_file() and 'historical' in history.read_text().lower(), 'D3', 'history index declares non-current status')
    verify_documented_versions(root, checks)
    verify_links(root, paths, checks, work)
    # Accuracy is deliberately a manual review, never inferred from keyword presence.
    documents = [str(p) for p in current if p.name == 'README.md' or p.parts[0] == 'docs'
                 or p.name in ('CONTRIBUTING.md', 'CHANGELOG.md')]
    (work / 'manual-review-documents.json').write_text(json.dumps(documents, indent=2) + '\n')
    print('MANUAL D4 review required for ' + str(len(documents)) + ' documents; no automated accuracy verdict')


def verify_links(root, paths, checks, work):
    remote = git(root, 'ls-remote', '--tags', 'origin')
    (work / 'remote-tags.txt').write_text(remote.stdout + remote.stderr)
    checks.check(remote.returncode == 0, 'D5', 'remote tag inventory available (read-only)')
    tag_objects = {line.split()[0] for line in remote.stdout.splitlines() if line.split()[1].endswith('^{}')}
    peeled_names = {line.split()[1][:-3] for line in remote.stdout.splitlines() if line.split()[1].endswith('^{}')}
    tag_objects.update(line.split()[0] for line in remote.stdout.splitlines() if line.split()[1] not in peeled_names)
    for path in paths:
        text = (root / path).read_text()
        # Fenced examples are not rendered links. Inline links, reference definitions and HTML links are.
        text = re.sub(r'(?ms)^```.*?^```[^\n]*$', '', text)
        links = re.findall(r'\]\(<?([^\s>)]+)>?(?:\s+"[^"]*")?\)', text)
        links += re.findall(r'(?m)^\s*\[[^\]]+\]:\s*<?([^\s>]+)', text)
        links += re.findall(r'(?:href|src)=["\']([^"\']+)["\']', text)
        links += re.findall(r'https://github\.com/SAP/smart-test-picker/(?:blob|tree|commit)/[^\s<>`\)]+', text)
        for link in sorted(set(links)):
            parsed = urllib.parse.urlparse(link)
            if parsed.scheme or parsed.netloc:
                match = re.match(r'https://github\.com/SAP/smart-test-picker/(blob|tree|commit)/(.+)', link, re.I)
                if not match:
                    continue
                tail = match.group(2).split('#', 1)[0]
                candidates = [(tail[:i], tail[i+1:]) for i, c in enumerate(tail) if c == '/'] + [(tail, '')]
                resolved = None
                for ref, filename in reversed(candidates):
                    revision = git(root, 'rev-parse', '--verify', ref + '^{commit}')
                    if revision.returncode:
                        revision = git(root, 'rev-parse', '--verify', 'origin/' + ref + '^{commit}')
                    if revision.returncode:
                        continue
                    sha = revision.stdout.strip()
                    if filename and git(root, 'cat-file', '-e', sha + ':' + filename).returncode:
                        continue
                    resolved = sha
                    break
                reachable = resolved and any(git(root, 'merge-base', '--is-ancestor', resolved, tag).returncode == 0 for tag in tag_objects)
                checks.check(bool(reachable), 'D5', str(path) + ' self-link tag-reachable: ' + link
                             + (' (branch-only or tag object unavailable)' if resolved and not reachable else ''))
            elif parsed.path:
                destination = root / parsed.path.lstrip('/') if parsed.path.startswith('/') else root / path.parent / urllib.parse.unquote(parsed.path)
                checks.check(destination.exists(), 'D5', str(path) + ' -> ' + link)


def verify_bundle(args, checks, work):
    checks.check(args.bundle.is_file(), 'A4', 'signed local Maven-layout bundle exists')
    if not args.bundle.is_file():
        return
    with tempfile.TemporaryDirectory(dir=work, prefix='bundle-') as directory:
        destination = Path(directory)
        with zipfile.ZipFile(args.bundle) as bundle:
            names = bundle.namelist()
            if len(names) != len(set(names)) or any(PurePosixPath(n).is_absolute() or '..' in PurePosixPath(n).parts or '\\' in n for n in names):
                raise ValueError('Unsafe or duplicate bundle entry')
            bundle.extractall(destination)
        expected = set()
        payloads = [p for p in destination.rglob('*') if p.is_file() and p.suffix in ('.jar', '.pom', '.module')]
        checks.check(bool(payloads), 'A4', 'bundle has artifact payloads')
        keyring = os.environ.get('STP_READINESS_PUBLIC_KEYRING')
        checks.check(bool(keyring) and Path(keyring).is_file(), 'A4', 'explicit throwaway verification public keyring')
        for payload in payloads:
            expected.add(payload)
            checks.check('-SNAPSHOT' not in str(payload.relative_to(destination)), 'A4', payload.name + ' non-SNAPSHOT')
            for algorithm in HASHES:
                checksum = Path(str(payload) + '.' + algorithm)
                expected.add(checksum)
                checks.check(checksum.is_file() and checksum.read_text().strip() == hashlib.new(algorithm, payload.read_bytes()).hexdigest(),
                             'A4', payload.name + ' ' + algorithm)
            signature = Path(str(payload) + '.asc')
            expected.add(signature)
            checks.check(signature.is_file(), 'A4', payload.name + ' signature')
            if keyring and signature.is_file():
                verified = subprocess.run(['gpgv', '--homedir', directory, '--keyring', str(Path(keyring).resolve()),
                                           str(signature), str(payload)], capture_output=True, text=True)
                checks.check(verified.returncode == 0, 'A4', payload.name + ' verified signature')
        checks.check(expected == {p for p in destination.rglob('*') if p.is_file()}, 'A4', 'no unexpected/unsigned payloads')
        args.repository = destination
        verify_artifacts(args, checks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('artifacts', 'docs', 'versions', 'java17', 'bundle'))
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--model', type=Path, default=Path('build/readiness/model.json'))
    parser.add_argument('--repository', type=Path, default=Path('build/readiness/staging'))
    parser.add_argument('--bundle', type=Path, default=Path('build/readiness/central-bundle.zip'))
    args = parser.parse_args()
    args.root = args.root.resolve()
    work = args.root / 'build/readiness/checks' / args.mode
    work.mkdir(parents=True, exist_ok=True)
    checks = Checks()
    try:
        if args.mode == 'artifacts': verify_artifacts(args, checks)
        elif args.mode == 'docs': verify_docs(args, checks, work)
        elif args.mode == 'versions': verify_documented_versions(args.root, checks)
        elif args.mode == 'java17': verify_java17(args, checks, work)
        else: verify_bundle(args, checks, work)
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile) as error:
        checks.check(False, args.mode, str(error))
    return checks.finish(work / 'results.json')


if __name__ == '__main__':
    sys.exit(main())
