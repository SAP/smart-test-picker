Feature: Scenario Outline executions have distinct identities

Scenario Outline: record example <operation>
  Given url baseUrl
  And path 'record'
  And param operation = '<operation>'
  When method get
  Then status 200

Examples:
  | operation |
  | outline-one |
  | outline-two |
