def r0 = '9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c'
def manifest = 'build/stp/mapping-manifest.json'
def stp = 'com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:'

def checkoutR0 = {
  deleteDir()
  sh 'git clone --no-hardlinks --recurse-submodules /opt/ei11/sonar-java .'
  sh "git checkout --detach ${r0} && test \"\$(git rev-parse HEAD)\" = ${r0}"
  writeFile file: '.ei12-settings.xml', text: '''<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"><profiles><profile><id>ei12</id><repositories>
    <repository><id>stp</id><url>file:///opt/stp-m2</url></repository><repository><id>host</id><url>file:///opt/host-m2</url></repository><repository><id>central</id><url>https://repo.maven.apache.org/maven2</url></repository>
  </repositories><pluginRepositories><pluginRepository><id>stp</id><url>file:///opt/stp-m2</url></pluginRepository><pluginRepository><id>host</id><url>file:///opt/host-m2</url></pluginRepository><pluginRepository><id>central</id><url>https://repo.maven.apache.org/maven2</url></pluginRepository></pluginRepositories></profile></profiles><activeProfiles><activeProfile>ei12</activeProfile></activeProfiles></settings>'''
  sh 'java -version && JAVA_TOOL_OPTIONS=-Xmx4g mvn -version && hostname && git status --short'
}

def inventory = { String pom, String profiles, String profile, String filter, String output ->
  def profileArg = profiles ? "-${profiles.startsWith('P') ? profiles : 'P' + profiles}" : ''
  def filterArg = filter ? " -DsmartTestPicker.testFilter=${filter}" : ''
  sh """JAVA_TOOL_OPTIONS=-Xmx4g mvn -s .ei12-settings.xml -B -ntp -Ddevelocity.skip=true -DskipTests -f ${pom} ${profileArg} process-test-classes ${stp}generate-reactor-head-test-inventory \\
    -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.prHeadRevision=${r0} \\
    -DsmartTestPicker.executionType=surefire -DsmartTestPicker.executionId=default-test \\
    -DsmartTestPicker.executionProfile=${profile} -DsmartTestPicker.reactorRoot=\$WORKSPACE${filterArg} \\
    -DsmartTestPicker.outputFile=\$WORKSPACE/${output}"""
}

node('stp-map-agent-3') {
  stage('EI-12 prepare complete matrix') {
    checkoutR0()
    manifest = stpPrepareCoverageMapping(shards: 3, schemaVersion: 3, revision: r0,
      buildTool: 'maven', collector: 'JACOCO', testTarget: 'complete-ci-matrix',
      inventory: 'target/head-test-inventory.json', project: 'SonarJava', branch: 'ei12-r0') {
      sh 'JAVA_TOOL_OPTIONS=-Xmx4g mvn -s .ei12-settings.xml -B -ntp -Ddevelocity.skip=true -DskipTests install'
      inventory('pom.xml', '', 'unit', '', 'build/stp/inventory-unit.json')
      inventory('its/plugin/pom.xml', 'it-plugin', 'it-plugin', '', 'build/stp/inventory-plugin.json')
      inventory('its/ruling/pom.xml', 'it-ruling,without-sonarqube-project', 'without-sonarqube-project', '', 'build/stp/inventory-ruling-without.json')
      inventory('its/ruling/pom.xml', 'it-ruling,only-sonarqube-project', 'only-sonarqube-project', '', 'build/stp/inventory-ruling-only.json')
      inventory('sonar-java-plugin/pom.xml', 'sanity', 'sanity', 'org.sonar.plugins.java.SanityTest', 'build/stp/inventory-sanity.json')
      inventory('docs/java-custom-rules-example/pom_SQ_10_6_LATEST.xml', '', 'sq-10.6-latest', '', 'build/stp/inventory-custom-rules.json')
      inventory('its/ruling/pom.xml', 'it-ruling', 'vibebot', 'org.sonar.java.it.JavaRulingTest#vibebot', 'build/stp/inventory-vibebot.json')
      sh "JAVA_TOOL_OPTIONS=-Xmx4g mvn -s .ei12-settings.xml -B -ntp ${stp}merge-executable-head-test-inventories -DsmartTestPicker.inputFiles=build/stp/inventory-unit.json,build/stp/inventory-plugin.json,build/stp/inventory-ruling-without.json,build/stp/inventory-ruling-only.json,build/stp/inventory-sanity.json,build/stp/inventory-custom-rules.json,build/stp/inventory-vibebot.json -DsmartTestPicker.outputFile=target/head-test-inventory.json"
    }
    sh '''python3 - <<'PY'
import json
from pathlib import Path
i=json.loads(Path('target/head-test-inventory.json').read_text())
assert i['revision']=='9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c'
assert len(i['tests'])==4261, len(i['tests'])
a=[]
for p in sorted(Path('build/stp/shards').glob('assignment-*.json')):
 v=json.loads(p.read_text()); a += v['tests']; print('EI12_ASSIGNMENT',v['shardId'],len(v['tests']))
assert len(a)==len(set(a))==len(i['tests']) and set(a)==set(i['tests'])
print('EI12_INVENTORY',len(i['tests']))
PY'''
    stash name: 'ei12-preparation', includes: 'target/head-test-inventory.json,build/stp/**/*.json,.ei12-settings.xml'
  }
}

def runScope = { String pom, String profiles, String profile, String lifecycle ->
  def profileArg = profiles ? "-${profiles.startsWith('P') ? profiles : 'P' + profiles}" : ''
  sh """JAVA_TOOL_OPTIONS=-Xmx4g mvn -s .ei12-settings.xml -B -ntp -Ddevelocity.skip=true -f ${pom} ${profileArg} \\
    -Dmaven.test.additionalClasspath=/opt/stp-m2/com/sap/oss/smart-test-picker/smart-test-picker-core/0.1.0/smart-test-picker-core-0.1.0.jar \\
    -Djunit.platform.listeners.autodetection.enabled=true -Djunit.jupiter.extensions.autodetection.enabled=true \\
    -DsmartTestPicker.completeInventoryFile=\$STP_MAVEN_COMPLETE_INVENTORY \\
    -DsmartTestPicker.executionType=surefire -DsmartTestPicker.executionId=default-test \\
    -DsmartTestPicker.executionProfile=${profile} -DsmartTestPicker.reactorRoot=\$WORKSPACE \\
    \"\$STP_MAVEN_MAPPING_GOAL\" ${lifecycle} ${stp}generate-reports ${stp}generate-coverage-fragment"""
}

def runShard = { String nodeName, String shardId ->
  node(nodeName) {
    stage("EI-12 map ${shardId}") {
      checkoutR0(); unstash 'ei12-preparation'
      sh "mkdir -p build/stp && printf 'shard=${shardId}\\nnode=${nodeName}\\nhostname=%s\\nworkspace=%s\\nstart=%s\\n' \"\$(hostname)\" \"\$(pwd)\" \"\$(date -u +%Y-%m-%dT%H:%M:%SZ)\" > build/stp/timeline-${shardId}.txt"
      stpCoverageMap(tool: 'maven', mapping: manifest, shardId: shardId) {
        runScope('pom.xml', '', 'unit', 'test')
        runScope('its/plugin/pom.xml', 'it-plugin', 'it-plugin', 'package')
        runScope('its/ruling/pom.xml', 'it-ruling,without-sonarqube-project', 'without-sonarqube-project', 'package')
        runScope('its/ruling/pom.xml', 'it-ruling,only-sonarqube-project', 'only-sonarqube-project', 'package')
        runScope('sonar-java-plugin/pom.xml', 'sanity', 'sanity', 'test -Dforce.sanity.test=true')
        runScope('docs/java-custom-rules-example/pom_SQ_10_6_LATEST.xml', '', 'sq-10.6-latest', 'test')
        runScope('its/ruling/pom.xml', 'it-ruling', 'vibebot', 'test')
        sh '''FRAGMENTS=$(find . -path '*/target/stp/coverage-fragment-v3-*.json' -type f | sort | paste -sd, -)
EVIDENCE=$(find . -path '*/target/stp/execution-evidence-v2-*.json' -type f | sort | paste -sd, -)
test -n "$FRAGMENTS" && test -n "$EVIDENCE"
JAVA_TOOL_OPTIONS=-Xmx4g mvn -s .ei12-settings.xml -B -ntp "$STP_MAVEN_MERGE_GOAL" -DsmartTestPicker.fragmentFiles="$FRAGMENTS" -DsmartTestPicker.evidenceFiles="$EVIDENCE"'''
      }
      sh "printf 'finish=%s\\n' \"\$(date -u +%Y-%m-%dT%H:%M:%SZ)\" >> build/stp/timeline-${shardId}.txt"
      stash name: "ei12-timeline-${shardId}", includes: "build/stp/timeline-${shardId}.txt"
    }
  }
}

parallel(
  'shard-0': { runShard('stp-agent', '0') },
  'shard-1': { runShard('stp-map-agent-1', '1') },
  'shard-2': { runShard('stp-map-agent-2', '2') }
)

node('stp-map-agent-3') {
  stage('EI-12 join and publish') {
    checkoutR0(); unstash 'ei12-preparation'
    ['0','1','2'].each { unstash "ei12-timeline-${it}" }
    def storage = stpPublishCoverageMap(mapping: manifest, project: 'SonarJava', branch: 'ei12-r0',
      build: r0, output: 'build/stp/coverage-map-ei12.json', storageRoot: '/opt/ei11/map-storage')
    echo "EI12_STORAGE_ROOT=${storage}"
    sh '''sha256sum build/stp/coverage-map-ei12.json
python3 - <<'PY'
import json
from pathlib import Path
m=json.loads(Path('build/stp/coverage-map-ei12.json').read_text())
assert m['revision']=='9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c' and len(m['tests'])==4261
print('EI12_PUBLISHED_TESTS',len(m['tests']))
PY'''
    archiveArtifacts artifacts: 'target/head-test-inventory.json,build/stp/**', fingerprint: true
  }
}
