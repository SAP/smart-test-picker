def R0 = '9bbe6d04d2db75e5b864f3a424bb78bfa4f36b4c'
def R1 = 'eed6922c776e5d029266459d04ed5a69e9d6fc8f'

node('stp-map-agent-3') {
  stage('Checkout exact R1') {
    deleteDir()
    sh "git clone --no-hardlinks /opt/ei11/sonar-java . && git checkout --detach ${R1} && test \$(git rev-parse HEAD) = ${R1}"
    writeFile file: '.ei11-settings.xml', text: '''<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <profiles><profile><id>ei11</id><repositories>
    <repository><id>stp-local</id><url>file:///opt/stp-m2</url></repository>
    <repository><id>host-cache</id><url>file:///opt/host-m2</url></repository>
    <repository><id>central</id><url>https://repo.maven.apache.org/maven2</url></repository>
  </repositories><pluginRepositories>
    <pluginRepository><id>stp-local</id><url>file:///opt/stp-m2</url></pluginRepository>
    <pluginRepository><id>host-cache</id><url>file:///opt/host-m2</url></pluginRepository>
    <pluginRepository><id>central</id><url>https://repo.maven.apache.org/maven2</url></pluginRepository>
  </pluginRepositories></profile></profiles><activeProfiles><activeProfile>ei11</activeProfile></activeProfiles>
</settings>'''
    sh 'hostname > build-hostname.txt; printf "node=%s\nworkspace=%s\n" "$NODE_NAME" "$WORKSPACE" > build-node.txt; java -version; mvn -version'
  }
  stage('Lookup published R0 map') {
    def lookup = stpLookupCoverageMap(project: 'SonarJava', branch: 'ei11-r0',
      requiredRevision: R0, storageRoot: '/opt/ei11/map-storage',
      output: 'build/stp/resolved-r0-map.json')
    if (lookup.policyAction != 'CONTINUE') { error("R0 lookup did not continue: ${lookup}") }
    env.EI11_MAP = lookup.coverageMap
  }
  stage('Generate authoritative R1 Maven inventory') {
    env.EI11_HEAD = stpHeadInventory(revision: R1, output: 'target/head-test-inventory.json') {
      sh '''mvn -s .ei11-settings.xml -B -ntp -Ddevelocity.skip=true process-test-classes \
        com.sap.oss.smart-test-picker:smart-test-picker-maven:0.1.0:generate-reactor-head-test-inventory \
        -DsmartTestPicker.schemaVersion=3 -DsmartTestPicker.prHeadRevision=eed6922c776e5d029266459d04ed5a69e9d6fc8f'''
    }
  }
  stage('Explicit R0 to R1 selection') {
    def decision = stpExplicitPrSelect(coverageMap: env.EI11_MAP, headInventory: env.EI11_HEAD,
      integrationRevision: R0, prBaseRevision: R0, prHeadRevision: R1,
      result: 'build/stp/selection-result.json', plan: 'build/stp/execution-plan.json')
    if (decision.policyAction != 'CONTINUE') { error("selection did not continue: ${decision}") }
    env.EI11_PLAN = decision.plan
    sh '''python3 - <<'PY'
import json
from pathlib import Path
p=json.loads(Path('build/stp/execution-plan.json').read_text())
assert p['mode']=='SELECT' and p['tests']
assert len(p['tests']) < 4161
print('EI11_PLAN_MODE',p['mode'])
print('EI11_PLAN_COUNT',len(p['tests']))
PY'''
  }
  stage('Execute exactly selected Maven identities') {
    smartTestPicker(tool: 'maven', plan: env.EI11_PLAN) {
      sh 'mvn -s .ei11-settings.xml -B -ntp -Ddevelocity.skip=true test'
    }
  }
  stage('Create and archive execution evidence') {
    sh '''python3 - <<'PY'
import json,re,xml.etree.ElementTree as ET
from pathlib import Path
executed=[]; skipped=[]
for report in sorted(Path('.').glob('**/target/surefire-reports/TEST-*.xml')):
 module=str(report).split('/target/',1)[0] or '.'
 for case in ET.parse(report).getroot().iter('testcase'):
  method=re.sub(r'[\\[(].*$', '', case.attrib['name'])
  identity=f'maven:{module}::{case.attrib["classname"]}#{method}'
  (skipped if case.find('skipped') is not None else executed).append(identity)
out={'version':1,'revision':'eed6922c776e5d029266459d04ed5a69e9d6fc8f','EXECUTED':sorted(set(executed)),'SKIPPED':sorted(set(skipped))}
Path('build/stp/actual-maven-execution.json').write_text(json.dumps(out,indent=2)+'\\n')
plan=json.loads(Path('build/stp/execution-plan.json').read_text())
logical={x['class']+'#'+x['method'] for x in plan['tests']}
actual={x.split('::',1)[1] for x in out['EXECUTED']}
assert actual == logical, f'plan/execution mismatch missing={sorted(logical-actual)} outside={sorted(actual-logical)}'
print('EI11_ACTUAL_EXECUTED',len(out['EXECUTED']))
PY
sha256sum build/stp/*.json'''
    archiveArtifacts artifacts: 'build/stp/*.json,target/head-test-inventory.json,**/target/surefire-reports/TEST-*.xml,build-hostname.txt,build-node.txt', fingerprint: true
  }
}
