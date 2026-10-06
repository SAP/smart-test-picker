# STP Karate client

This module adds `X-STP-Test-Suite-Id` and `X-STP-Test-Id` to every outgoing Karate HTTP request. It targets Karate 2.1.2 and Java 21 or newer.

Install the integration once in the suite's `karate-config.js`; feature files need no STP-specific steps:

```javascript
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
```

Supply a suite identity once for the test run, for example:

```text
-Dstp.testSuiteId=checkout-regression
```

The dynamic `configure headers` function runs for each outgoing request and receives the current Karate request. The client derives TestID from these Karate runtime fields:

- `karate.feature.prefixedPath` — identifies the feature resource;
- `karate.scenario.sectionIndex` — identifies the Scenario section in that feature;
- `karate.scenario.line` — identifies the Scenario declaration;
- `karate.scenario.exampleIndex` — distinguishes Scenario Outline rows (`-1` for a regular Scenario).

The module hashes the UTF-8 string `featurePath + newline + sectionIndex + newline + scenarioLine + newline + exampleIndex` with SHA-256 and prefixes its lowercase hexadecimal value with `karate-`. This yields a stable 71-character TestID. Each row in an Outline gets a different ID; multiple requests from the same Scenario reuse its ID. SuiteID remains independent, so the same Scenario can be run under different suites.

If either STP header is already present with the generated value, it is accepted. A different value or duplicate conflicting header fails the scenario before the HTTP request is sent. TestID and TestSuiteID values must be non-empty, at most 256 characters, and contain no ISO control characters.

No shared mutable identity state is used. Karate supplies scenario metadata to the callback executing for that request, so parallel Scenarios derive their own IDs.

## Validation

Run:

```text
./gradlew :stp-karate-client:test
```

The tests start a local HTTP server and run ordinary Karate feature files through Karate's `Runner` with the suite-wide configuration loaded from `karate-config.js`.
