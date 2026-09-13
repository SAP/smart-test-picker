def R1 = 'a11311c52a72cf1a6cef50f2942ffb79dfc4a69d'

node('stp-map-agent-3') {
  stage('Checkout R1') {
    deleteDir()
    sh "git clone --no-hardlinks /opt/ei10/spring-framework . && git checkout --detach ${R1} && test \$(git rev-parse HEAD) = ${R1}"
    sh 'hostname > build-hostname.txt; printf "node=%s\\nworkspace=%s\\n" "$NODE_NAME" "$WORKSPACE" > build-node.txt'
  }
  stage('Run complete Spring Core suite') {
    sh './gradlew :spring-core:test --no-daemon --stacktrace'
  }
  stage('Archive full-suite evidence') {
    archiveArtifacts artifacts: 'spring-core/build/test-results/test/*.xml,build-hostname.txt,build-node.txt', fingerprint: true
  }
}
