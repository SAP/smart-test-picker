Feature: A workflow scenario can make several service calls

Scenario: checkout calls several endpoints
  Given url baseUrl
  And path 'record'
  And param operation = 'step-one'
  When method get
  Then status 200
  Given url baseUrl
  And path 'record'
  And param operation = 'step-two'
  When method get
  Then status 200
