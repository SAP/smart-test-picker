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
  stage('Run full R1 Maven test scope') {
    sh 'mvn -s .ei11-settings.xml -B -ntp -Ddevelocity.skip=true test'
  }
  stage('Create full-suite execution evidence') {
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
Path('full-r1-execution.json').write_text(json.dumps(out,indent=2)+'\\n')
assert len(out['EXECUTED']) > 14
print('EI11_FULL_EXECUTED',len(out['EXECUTED']))
print('EI11_FULL_SKIPPED',len(out['SKIPPED']))
PY
sha256sum full-r1-execution.json'''
    archiveArtifacts artifacts: 'full-r1-execution.json,**/target/surefire-reports/TEST-*.xml,build-hostname.txt,build-node.txt', fingerprint: true
  }
}
