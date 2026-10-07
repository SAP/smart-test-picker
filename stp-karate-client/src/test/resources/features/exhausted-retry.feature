Feature: Exhausted retry budget
Scenario: server keeps failing
  * configure retry = { count: 2, interval: 1 }
  Given url baseUrl
  And path 'record'
  And param operation = 'exhausted-retry'
  And retry until responseStatus == 200
  When method get
  Then status 200
