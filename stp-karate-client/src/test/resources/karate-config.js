function fn() {
  // Ordinary application configuration; STP is installed once on the Java Runner.
  karate.configure('headers', function(request) {
    return { Authorization: 'Bearer fixture-token', 'X-Configured': 'preserved' };
  });
  return {
    baseUrl: karate.properties['fixture.baseUrl'],
    injectedHeaders: JSON.parse(karate.properties['fixture.headers'] || '{}')
  };
}
