Feature: Parallel Karate scenarios

Scenario: first concurrent scenario
  Given url baseUrl
  And path 'record'
  And param operation = 'parallel-one'
  When method get
  Then status 200

Scenario: second concurrent scenario
  Given url baseUrl
  And path 'record'
  And param operation = 'parallel-two'
  When method get
  Then status 200
