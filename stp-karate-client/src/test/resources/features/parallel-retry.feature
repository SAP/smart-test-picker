Feature: Concurrent retries
Background:
  * configure retry = { count: 3, interval: 1 }
Scenario: first retrying scenario
  Given url baseUrl
  And path 'record'
  And param operation = 'parallel-retry-one'
  And retry until responseStatus == 200
  When method get
  Then status 200
Scenario: second retrying scenario
  Given url baseUrl
  And path 'record'
  And param operation = 'parallel-retry-two'
  And retry until responseStatus == 200
  When method get
  Then status 200
