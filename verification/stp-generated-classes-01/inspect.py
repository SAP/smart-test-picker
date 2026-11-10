#!/usr/bin/env python3
"""Read retained measurement outputs; write bounded, reproducible inspection evidence."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

here = Path(__file__).resolve().parent
repo = here.parent.parent
work = Path(sys.argv[1]).resolve()
prior = repo / 'verification/stp-petclinic-parity-01/raw'
raw = here / 'raw'
raw.mkdir(exist_ok=True)

def javap(classpath, name):
    return subprocess.check_output(['javap', '-p', '-c', '-classpath', str(classpath), name], text=True)

def classes(root):
    return {str(p.relative_to(root))[:-6].replace('/', '.'): p for p in root.rglob('*.class')}

result = {}
for tool, directory in [('maven', 'target/classes'), ('gradle', 'build/classes/java/main')]:
    compiled = classes(work / tool / directory)
    diagnostic = json.loads((prior / ('maven/stp/asm-agent-diagnostic.json' if tool == 'maven'
                                     else 'gradle/agent-diagnostic.json')).read_text())
    keys = sorted({k for entry in diagnostic['methodCatalog'] for k in entry['canonicalKeys']})
    catalog_classes = sorted({k.split('#')[0] for k in keys})
    generated = classes(work / tool / ('target' if tool == 'maven' else 'build/generated/aotTestClasses'))
    accessors = {k: p for k, p in generated.items() if '__Accessor_' in k}
    result[tool] = {
        'metrics': diagnostic['metrics'],
        'compiledClasses': sorted(compiled),
        'catalogClasses': catalog_classes,
        'catalogMethods': keys,
        'compiledWithoutCatalog': sorted(set(compiled) - set(catalog_classes)),
        'catalogOutsideCompiled': sorted(set(catalog_classes) - set(compiled)),
        'accessors': {k: {'path': str(p.relative_to(work)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                      for k, p in sorted(accessors.items())},
    }
    for kind, listing in [('accessors', accessors), ('entities', {
        k: p for k, p in compiled.items()
        if k.rsplit('.', 1)[-1] in {'Owner', 'Pet', 'Visit', 'PetType', 'Vet', 'Specialty', 'BaseEntity', 'NamedEntity', 'Person'}
    })]:
        sections = []
        for name, path in sorted(listing.items()):
            sections.append('FILE ' + str(path.relative_to(work)) + '\n'
                            + subprocess.check_output(['javap', '-p', '-c', str(path)], text=True))
        disassembly = '\n'.join(sections)
        (raw / f'{tool}-{kind}-javap.txt').write_text(disassembly)
        if kind == 'entities':
            markers = ['$$_hibernate_', 'PersistentAttributeInterceptable', 'SelfDirtinessTracker', 'ManagedEntity']
            result[tool]['entityEnhancementMarkers'] = [marker for marker in markers if marker in disassembly]
            assert not result[tool]['entityEnhancementMarkers']

assert len(result['maven']['compiledClasses']) == 30
assert len(result['gradle']['compiledClasses']) == 25
assert len(result['maven']['catalogClasses']) == 23
assert len(result['gradle']['catalogClasses']) == 29
assert len(result['gradle']['accessors']) == 6 and not result['maven']['accessors']
assert set(result['gradle']['catalogOutsideCompiled']) == set(result['gradle']['accessors'])
extra_methods = set(result['gradle']['catalogMethods']) - set(result['maven']['catalogMethods'])
assert len(extra_methods) == 30 and all('__Accessor_' in method for method in extra_methods)
assert not (set(result['maven']['catalogMethods']) - set(result['gradle']['catalogMethods']))
assert result['maven']['metrics']['classesIncluded'] == len(result['maven']['compiledClasses']) == 30
assert result['gradle']['metrics']['classesIncluded'] == len(result['gradle']['compiledClasses']) + 6 == 31
for tool in ['maven', 'gradle']:
    assert result[tool]['metrics']['classesTransformed'] == len(result[tool]['catalogClasses'])
(raw / 'class-accounting.json').write_text(json.dumps(result, indent=2) + '\n')

jar = work / 'm2/org/springframework/data/spring-data-commons/4.1.0/spring-data-commons-4.1.0.jar'
names = ['org.springframework.data.aot.AotMappingContext',
         'org.springframework.data.aot.AotMappingContext$AotAccessorFactory',
         'org.springframework.data.aot.DefaultAotContext$ContextualTypeConfiguration',
         'org.springframework.data.aot.DefaultAotContext$AccessorContributionConfiguration',
         'org.springframework.data.mapping.model.ClassGeneratingPropertyAccessorFactory',
         'org.springframework.data.mapping.model.ClassGeneratingPropertyAccessorFactory$PropertyAccessorClassGenerator']
(raw / 'spring-data-generator-javap.txt').write_text(
    'JAR ' + str(jar) + '\nSHA256 ' + hashlib.sha256(jar.read_bytes()).hexdigest() + '\n\n'
    + '\n'.join(javap(jar, name) for name in names))

with (raw / 'retained-log-excerpts.txt').open('w') as out:
    for path in [prior / 'gradle-mapping.log', prior / 'maven/mapping.log', prior / 'gradle/fork-classpath.txt']:
        for number, line in enumerate(path.read_text().splitlines(), 1):
            if any(token in line for token in ['Tasks to be executed:', '> Task :processTestAot',
                    'No LoadTimeWeaver', 'generated/aotTestClasses', '--- spring-boot:', '--- native:', '--- hibernate:']):
                out.write(f'{path.relative_to(repo)}:{number}: {line}\n')

print(json.dumps({tool: {k: value[k] for k in ['compiledWithoutCatalog', 'catalogOutsideCompiled']}
                  for tool, value in result.items()}, indent=2))
print('PASS: compiled/catalog/accessor set assertions')
