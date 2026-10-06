Feature: A different feature file

Scenario: another single request
  Given url baseUrl
  And path 'record'
  And param operation = 'other-feature'
  When method get
  Then status 200
