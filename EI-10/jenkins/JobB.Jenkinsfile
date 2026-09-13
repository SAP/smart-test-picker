def R0 = 'a099debca9e59eb562cb3298690423dbb9d0127e'
def R1 = 'a11311c52a72cf1a6cef50f2942ffb79dfc4a69d'

node('stp-map-agent-3') {
  stage('Checkout R1') {
    deleteDir()
    sh "git clone --no-hardlinks /opt/ei10/spring-framework . && git checkout --detach ${R1} && test \$(git rev-parse HEAD) = ${R1}"
    sh 'hostname > build-hostname.txt; printf "node=%s\\nworkspace=%s\\n" "$NODE_NAME" "$WORKSPACE" > build-node.txt'
  }
  stage('Lookup R0 map') {
    def lookup = stpLookupCoverageMap(project: 'spring-framework', branch: 'ei10-r0',
      requiredRevision: R0, storageRoot: '/opt/ei10/map-storage',
      output: 'build/stp/resolved-r0-map.json')
    if (lookup.policyAction != 'CONTINUE') { error("R0 lookup did not continue: ${lookup}") }
    env.EI10_MAP = lookup.coverageMap
  }
  stage('Generate R1 head inventory') {
    env.EI10_HEAD = stpHeadInventory(revision: R1,
      output: 'spring-core/build/head-test-inventory.json') {
      sh './gradlew :spring-core:generateHeadTestInventory --no-daemon --stacktrace'
    }
  }
  stage('Explicit R0 to R1 selection') {
    def decision = stpExplicitPrSelect(coverageMap: env.EI10_MAP,
      headInventory: env.EI10_HEAD,
      integrationRevision: R0, prBaseRevision: R0, prHeadRevision: R1,
      result: 'build/stp/selection-result.json', plan: 'build/stp/execution-plan.json')
    if (decision.policyAction != 'CONTINUE') { error("selection did not continue: ${decision}") }
    env.EI10_PLAN = decision.plan
  }
  stage('Execute exactly selected tests') {
    smartTestPicker(tool: 'gradle', plan: env.EI10_PLAN) {
      sh './gradlew :spring-core:test --no-daemon --stacktrace'
    }
  }
  stage('Publish evidence') {
    archiveArtifacts artifacts: 'build/stp/*.json,spring-core/build/head-test-inventory.json,spring-core/build/test-results/test/*.xml,build-hostname.txt,build-node.txt', fingerprint: true
  }
}
