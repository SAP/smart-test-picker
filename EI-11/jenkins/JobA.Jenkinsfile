def r0 = '9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c'
def mappingManifest = 'build/stp/mapping-manifest.json'

def configureMaven = {
  writeFile file: '.ei11-settings.xml', text: '''<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <profiles><profile><id>ei11</id><repositories>
    <repository><id>stp-local</id><url>file:///opt/stp-m2</url><releases><enabled>true</enabled></releases></repository>
    <repository><id>host-cache</id><url>file:///opt/host-m2</url><releases><enabled>true</enabled></releases></repository>
    <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url></repository>
  </repositories><pluginRepositories>
    <pluginRepository><id>stp-local</id><url>file:///opt/stp-m2</url></pluginRepository>
    <pluginRepository><id>host-cache</id><url>file:///opt/host-m2</url></pluginRepository>
    <pluginRepository><id>central</id><url>https://repo.maven.apache.org/maven2</url></pluginRepository>
  </pluginRepositories></profile></profiles><activeProfiles><activeProfile>ei11</activeProfile></activeProfiles>
</settings>'''
}

def checkoutRevision = {
  deleteDir()
  sh 'git clone --no-hardlinks /opt/ei11/sonar-java .'
  sh "git checkout --detach ${r0} && test \"\$(git rev-parse HEAD)\" = ${r0}"
  configureMaven()
  sh 'java -version && mvn -version'
}

node('stp-map-agent-3') {
  stage('Prepare') {
    checkoutRevision()
    mappingManifest = stpPrepareCoverageMapping(
      shards: 3,
      schemaVersion: 3,
      revision: r0,
      buildTool: 'maven',
      collector: 'JACOCO',
      testTarget: 'test',
      inventory: 'target/head-test-inventory.json',
      project: 'SonarJava',
      branch: 'ei11-r0') {
        sh '''mvn -s .ei11-settings.xml -B -ntp -Ddevelocity.skip=true \
          process-test-classes \
          com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:generate-reactor-head-test-inventory \
          -DsmartTestPicker.schemaVersion=3 \
          -DsmartTestPicker.prHeadRevision=9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c'''
    }
    sh '''python3 - <<'PY'
import json
from pathlib import Path
i=json.loads(Path('target/head-test-inventory.json').read_text())
assert i['revision'] == '9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c'
assert i['tests'] and all(t.startswith('maven:') and '::' in t for t in i['tests'])
assignments=[]
for p in sorted(Path('build/stp/shards').glob('assignment-*.json')):
    v=json.loads(p.read_text()); assignments.extend(v['tests'])
    print('EI11_ASSIGNMENT', v['shardId'], len(v['tests']))
assert len(assignments)==len(i['tests']) and len(set(assignments))==len(assignments)==len(set(i['tests']))
print('EI11_INVENTORY',len(i['tests']))
PY'''
    stash name: 'ei11-preparation', includes: 'target/head-test-inventory.json,build/stp/mapping-manifest.json,build/stp/shards/*.json,.ei11-settings.xml'
  }
}

def runShard = { String nodeName, String shardId ->
  node(nodeName) {
    stage("Map ${shardId}") {
      checkoutRevision()
      unstash 'ei11-preparation'
      sh "mkdir -p build/stp && { echo shard=${shardId}; echo node=${nodeName}; echo hostname=\$(hostname); echo workspace=\$(pwd); echo start=\$(date -u +%Y-%m-%dT%H:%M:%SZ); } > build/stp/timeline-${shardId}.txt"
      stpCoverageMap(tool: 'maven', mapping: mappingManifest, shardId: shardId) {
        sh '''mvn -s .ei11-settings.xml -B -ntp -Ddevelocity.skip=true \
          -Dmaven.test.additionalClasspath=/opt/stp-m2/com/sap/oss/smart-test-picker/smart-test-picker-core/0.1.0/smart-test-picker-core-0.1.0.jar \
          -Djunit.platform.listeners.autodetection.enabled=true \
          -Djunit.jupiter.extensions.autodetection.enabled=true \
          "$STP_MAVEN_MAPPING_GOAL" test \
          com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:generate-reports \
          com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:generate-coverage-fragment \
          com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:aggregate-reactor-coverage-fragment'''
      }
      sh "echo finish=\$(date -u +%Y-%m-%dT%H:%M:%SZ) >> build/stp/timeline-${shardId}.txt"
      stash name: "ei11-timeline-${shardId}", includes: "build/stp/timeline-${shardId}.txt"
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
    checkoutRevision()
    unstash 'ei11-preparation'
    unstash 'ei11-timeline-0'
    unstash 'ei11-timeline-1'
    unstash 'ei11-timeline-2'
    def storage = stpPublishCoverageMap(mapping: mappingManifest,
      project: 'SonarJava', branch: 'ei11-r0', build: r0,
      output: 'build/stp/coverage-map-ei11.json', storageRoot: '/opt/ei11/map-storage')
    echo "EI11_STORAGE_ROOT=${storage}"
    sh '''sha256sum build/stp/coverage-map-ei11.json
python3 - <<'PY'
import hashlib,json
from pathlib import Path
m=json.loads(Path('build/stp/coverage-map-ei11.json').read_text())
assert m['revision']=='9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c'
print('EI11_PUBLISHED_REVISION',m['revision'])
print('EI11_PUBLISHED_TESTS',len(m['tests']))
for p in sorted(Path('build/stp').glob('fragment-*.json')):
 print('EI11_FRAGMENT',p.name,hashlib.sha256(p.read_bytes()).hexdigest())
for p in sorted(Path('build/stp').glob('execution-evidence-*.json')):
 v=json.loads(p.read_text()); print('EI11_EVIDENCE',p.name,len(v.get('EXECUTED',[])),len(v.get('NON_EXECUTED',[])),hashlib.sha256(p.read_bytes()).hexdigest())
PY'''
    archiveArtifacts artifacts: 'target/head-test-inventory.json,build/stp/**', fingerprint: true
  }
}
