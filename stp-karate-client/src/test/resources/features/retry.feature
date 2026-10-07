Feature: Retry transient HTTP failure
Scenario: retry then continue
  * configure retry = { count: 3, interval: 1 }
  Given url baseUrl
  And path 'record'
  And param operation = 'retry'
  And retry until responseStatus == 200
  When method get
  Then status 200
  Given url baseUrl
  And path 'record'
  And param operation = 'after-retry'
  When method get
  Then status 200
