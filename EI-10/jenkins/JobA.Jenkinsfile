def r0 = 'a099debca9e59eb562cb3298690423dbb9d0127e'
def mappingManifest = 'build/stp/mapping-manifest.json'

node('stp-map-agent-3') {
  stage('Prepare') {
    deleteDir()
    sh 'git clone --no-hardlinks /opt/ei10/spring-framework .'
    sh "git checkout --detach ${r0} && test \"\$(git rev-parse HEAD)\" = ${r0}"
    mappingManifest = stpPrepareCoverageMapping(
      shards: 3,
      schemaVersion: 3,
      revision: r0,
      buildTool: 'gradle',
      collector: 'ASM',
      testTarget: ':spring-core:test',
      inventory: 'build/executable-head-test-inventory.json',
      project: 'spring-framework',
      branch: 'ei10-r0') {
        sh './gradlew "$STP_GRADLE_DISCOVERY_TASK" --no-daemon --stacktrace'
    }
    sh '''python3 - <<'PY'
import json
from pathlib import Path
inventory=json.loads(Path('build/executable-head-test-inventory.json').read_text())
assert inventory['revision'] == 'a099debca9e59eb562cb3298690423dbb9d0127e'
assert inventory['tests'] and all(t.startswith('gradle::spring-core:test::') for t in inventory['tests'])
for p in sorted(Path('build/stp/shards').glob('assignment-*.json')):
    value=json.loads(p.read_text())
    print(f"EI10_ASSIGNMENT {value['shardId']} {len(value['tests'])}")
print(f"EI10_INVENTORY {len(inventory['tests'])}")
PY'''
    stash name: 'ei10-preparation', includes: 'build/executable-head-test-inventory.json,build/stp/mapping-manifest.json,build/stp/shards/*.json'
  }
}

def runShard = { String nodeName, String shardId ->
  node(nodeName) {
    stage("Map ${shardId}") {
      deleteDir()
      sh 'git clone --no-hardlinks /opt/ei10/spring-framework .'
      sh "git checkout --detach ${r0} && test \"\$(git rev-parse HEAD)\" = ${r0}"
      unstash 'ei10-preparation'
      sh "mkdir -p build/stp && { echo shard=${shardId}; echo node=${nodeName}; echo hostname=\$(hostname); echo workspace=\$(pwd); echo start=\$(date -u +%Y-%m-%dT%H:%M:%SZ); } > build/stp/timeline-${shardId}.txt"
      stpCoverageMap(tool: 'gradle', mapping: mappingManifest, shardId: shardId,
        coverageIncludes: 'org.springframework.') {
          sh './gradlew "$STP_GRADLE_MAPPING_TASK" --no-daemon --stacktrace'
      }
      sh "echo finish=\$(date -u +%Y-%m-%dT%H:%M:%SZ) >> build/stp/timeline-${shardId}.txt"
      stash name: "ei10-timeline-${shardId}", includes: "build/stp/timeline-${shardId}.txt"
    }
  }
}

parallel(
  'shard-0': { runShard('stp-agent', '0') },
  'shard-1': { runShard('stp-map-agent-1', '1') },
  'shard-2': { runShard('stp-map-agent-2', '2') }
)

node('stp-map-agent-3') {
  stage('Join and publish') {
    deleteDir()
    sh 'git clone --no-hardlinks /opt/ei10/spring-framework .'
    sh "git checkout --detach ${r0} && test \"\$(git rev-parse HEAD)\" = ${r0}"
    unstash 'ei10-preparation'
    unstash 'ei10-timeline-0'
    unstash 'ei10-timeline-1'
    unstash 'ei10-timeline-2'
    def storage = stpPublishCoverageMap(mapping: mappingManifest,
      project: 'spring-framework', branch: 'ei10-r0', build: r0,
      output: 'build/stp/coverage-map-ei10.json', storageRoot: '/opt/ei10/map-storage')
    echo "EI10_STORAGE_ROOT=${storage}"
    sh '''sha256sum build/stp/coverage-map-ei10.json
python3 - <<'PY'
import hashlib,json
from pathlib import Path
m=json.loads(Path('build/stp/coverage-map-ei10.json').read_text())
print('EI10_PUBLISHED_REVISION',m['revision'])
print('EI10_PUBLISHED_TESTS',len(m['tests']))
for p in sorted(Path('build/stp').glob('fragment-*.json')):
 print('EI10_FRAGMENT',p.name,hashlib.sha256(p.read_bytes()).hexdigest())
for p in sorted(Path('build/stp').glob('execution-evidence-*.json')):
 v=json.loads(p.read_text()); print('EI10_EVIDENCE',p.name,len(v.get('EXECUTED',[])),hashlib.sha256(p.read_bytes()).hexdigest())
PY'''
    archiveArtifacts artifacts: 'build/executable-head-test-inventory.json,build/stp/**', fingerprint: true
  }
}
