# STP Karate client

This module adds `X-STP-Test-Suite-Id`, `X-STP-Test-Id`, and `X-STP-Request-Id` to every outgoing Karate HTTP request. It targets Karate 2.1.2 and Java 21 or newer.

Install the integration once in the suite's `karate-config.js`; feature files need no STP-specific steps:

```javascript
function fn() {
  var propertySuite = karate.sysprop('stp.testSuiteId');
  var environmentSuite = Java.type('java.lang.System').getenv('STP_TEST_SUITE_ID');
  var StpKarateClient = Java.type('com.sap.oss.smarttestpicker.karate.StpKarateClient');
  var suiteId = StpKarateClient.resolveSuiteId(propertySuite, environmentSuite);
  karate.configure('headers', function(request) {
    var scenario = karate.scenario;
    var feature = karate.feature;
    return StpKarateClient.headers(request, suiteId, feature.prefixedPath,
      scenario.sectionIndex, scenario.line, scenario.exampleIndex);
  });
  return {};
}
```

Supply a suite identity once for the test run, for example:

```text
-Dstp.testSuiteId=checkout-regression
```

The dynamic `configure headers` function runs for each outgoing request and receives the current Karate request. Supply TestSuiteID once using `-Dstp.testSuiteId=<id>` or `STP_TEST_SUITE_ID=<id>`. Either source may be used alone; equal values are accepted when both are supplied, while conflicting, missing, blank, overlong, or control-character values fail clearly.

The client derives TestID from these Karate runtime fields:

- `karate.feature.prefixedPath` — identifies the feature resource;
- `karate.scenario.sectionIndex` — identifies the Scenario section in that feature;
- `karate.scenario.line` — identifies the Scenario declaration;
- `karate.scenario.exampleIndex` — distinguishes Scenario Outline rows (`-1` for a regular Scenario).

The module hashes the UTF-8 string `featurePath + newline + sectionIndex + newline + scenarioLine + newline + exampleIndex` with SHA-256 and prefixes its lowercase hexadecimal value with `karate-`. This yields a stable 71-character TestID. Each row in an Outline gets a different ID; multiple requests from the same Scenario reuse its ID. SuiteID remains independent, so the same Scenario can be run under different suites. RequestID is a fresh random UUID for each HTTP request, so one scenario's separate requests stay distinct and repeated executions never reuse a request identity.

Matching existing SuiteID or TestID headers are accepted; conflicting or duplicate values fail before send. A single existing valid UUID RequestID is reused when Karate invokes its callback again for the same request object; duplicate or malformed values fail. SuiteID and TestID values must be non-empty, at most 256 characters, and contain no ISO control characters.

No shared mutable identity state is used. Karate supplies scenario metadata to the callback executing for that request, so parallel Scenarios derive their own IDs.

## Validation

Run:

```text
./gradlew :stp-karate-client:test
```

The tests start a local HTTP server and run ordinary Karate feature files through Karate's `Runner` with the suite-wide configuration loaded from `karate-config.js`.
