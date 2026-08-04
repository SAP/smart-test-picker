<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# Spring Data PetClinic diagnostics (reproduction fixture)

This unpublished module now contains only the small public-API diagnostics used
by the pinned PetClinic acceptance and advisor-lifecycle reproductions. It is
not a production adapter and is not on any production module's dependency
path.

The earlier disposable listener/advice/wrapper/mock comparison fixture has been
removed. Its normalized evidence is retained under
`docs/spikes/archive/spring-data-observability/`, and its conclusions remain in
the corresponding research documents.

`petclinicConfirmationJar` packages the acceptance and lifecycle diagnostics.
The JAR does not contain Spring, Spring Data, STP runtime, application, or test
classes. PetClinic supplies the public Spring APIs at test runtime. No publishing
configuration is present.

