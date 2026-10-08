Feature: Several HTTP methods in one scenario
Scenario: create inspect remove
  Given url baseUrl
  And path 'record'
  And param operation = 'post'
  And param token = 'query-secret'
  And header Cookie = 'session=cookie-secret'
  And request { password: 'body-secret' }
  When method post
  Then status 200
  Given url baseUrl
  And path 'record'
  And param operation = 'get'
  When method get
  Then status 200
  Given url baseUrl
  And path 'record'
  And param operation = 'delete'
  When method delete
  Then status 200
