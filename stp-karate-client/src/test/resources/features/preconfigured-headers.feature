Feature: Preserve compatible request headers

Scenario: request carries preconfigured headers
  * headers injectedHeaders
  Given url baseUrl
  And path 'record'
  And param operation = 'preconfigured'
  When method get
  Then status 200
