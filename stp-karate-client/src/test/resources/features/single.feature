Feature: A single request scenario

Scenario: same test in more than one suite
  Given url baseUrl
  And path 'record'
  And param operation = 'single'
  When method get
  Then status 200
