function fn() {
  var suiteId = karate.sysprop('stp.testSuiteId');
  var StpKarateClient = Java.type('com.sap.oss.smarttestpicker.karate.StpKarateClient');
  karate.configure('headers', function(request) {
    var scenario = karate.scenario;
    var feature = karate.feature;
    return StpKarateClient.headers(request, suiteId, feature.prefixedPath,
      scenario.sectionIndex, scenario.line, scenario.exampleIndex);
  });
  return {};
}
