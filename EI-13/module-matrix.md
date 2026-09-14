# Apache Commons Statistics 1.3 reactor matrix

R0 is tag `rel/commons-statistics-1.3` at `2937eb2e711483d8ea9dc216af45c16fd0066b77`. The release was selected because it is the latest stable upstream release at validation time, its POM requires Java 11 or newer, and it builds unchanged on the available Java 21/Maven 3.9 Docker image. The accepted functional command is `mvn --show-version --batch-mode --no-transfer-progress -Pexamples clean verify`.

All tests use JUnit Jupiter through inherited Surefire execution `default-test`. There is no Failsafe execution and no external service. Production/test counts below mean source trees are present, not Java file counts. Baseline counts are Surefire invocations (skipped in parentheses).

| module path | coordinates | packaging | activation | production | tests | execution target | engine | runtime | R0 baseline |
|---|---|---:|---|---:|---:|---|---|---|---:|
| `.` | `org.apache.commons:commons-statistics-parent:1.3` | pom | always | no | no | inherited Surefire, no tests | JUnit Jupiter | JDK 11+, none | 0 |
| `commons-statistics-distribution` | `org.apache.commons:commons-statistics-distribution:1.3` | jar | always | yes | yes | `surefire@default-test@examples` | JUnit Jupiter | JDK 11+, none | 11,135 (30) |
| `commons-statistics-descriptive` | `org.apache.commons:commons-statistics-descriptive:1.3` | jar | always | yes | yes | `surefire@default-test@examples` | JUnit Jupiter | JDK 11+, none | 31,001 (16) |
| `commons-statistics-ranking` | `org.apache.commons:commons-statistics-ranking:1.3` | jar | always | yes | yes | `surefire@default-test@examples` | JUnit Jupiter | JDK 11+, none | 46 |
| `commons-statistics-inference` | `org.apache.commons:commons-statistics-inference:1.3` | jar | always | yes | yes | `surefire@default-test@examples` | JUnit Jupiter | JDK 11+, none | 7,400 (2) |
| `commons-statistics-interval` | `org.apache.commons:commons-statistics-interval:1.3` | jar | always | yes | yes | `surefire@default-test@examples` | JUnit Jupiter | JDK 11+, none | 49 |
| `commons-statistics-bom` | `org.apache.commons:commons-statistics-bom:1.3` | pom | always | no | no | inherited Surefire, no tests | JUnit Jupiter | JDK 11+, none | 0 |
| `commons-statistics-docs` | `org.apache.commons:commons-statistics-docs:1.3` | jar | always | no | no | inherited Surefire, no tests | JUnit Jupiter | JDK 11+, none | 0 |
| `commons-statistics-examples` | `org.apache.commons:commons-statistics-examples:1.3` | pom | `examples` | no | no | inherited Surefire, no tests | JUnit Jupiter | JDK 11+, none | 0 |
| `commons-statistics-examples/examples-distribution` | `org.apache.commons:commons-statistics-examples-distribution:1.3` | jar | `examples` | yes | no | `surefire@default-test@examples`, empty | JUnit Jupiter | JDK 11+, none | 0 |
| `commons-statistics-examples/examples-jmh` | `org.apache.commons:commons-statistics-examples-jmh:1.3` | jar | `examples` | yes | yes | `surefire@default-test@examples` | JUnit Jupiter | JDK 11+, none | 6,248 |

The `release` profile adds `commons-statistics-dist-archive` (assembly/publication packaging) but no distinct functional suite. Signing, deployment/staging, site/documentation, Javadoc, japicmp, benchmark execution, and the CI JDK matrix are release/publication/compatibility workflows rather than additional functional test executions. The accepted `examples` profile is included because it adds two real nested reactor modules and the JMH module's tests. The union has 12 modules; the accepted functional reactor has 11.
