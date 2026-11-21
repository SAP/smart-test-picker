# Redistribution license texts

These are license resources for bundled dependencies, not licenses of STP source files.
The shaded artifacts retain dependency LICENSE, NOTICE and JaCoCo `about.html` files.
The build adds a complete license where the dependency jar omits it, and an index with
the source archive URL for each resolved version.

- ASM: BSD-3-Clause text from the `ClassReader.java` header in the
  [ASM 9.8 source archive](https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.8/asm-9.8-sources.jar).
- JaCoCo: [EPL-2.0](https://www.eclipse.org/legal/epl-2.0/); upstream attribution in
  `about.html` is retained, and exact-version sources are linked in the artifact index.
- Gson and picocli: the Apache-2.0 text in the repository's `LICENSES` directory.
- JAXB and activation components: their supplied LICENSE and NOTICE files are retained.

ASM packages are relocated by the agent's existing shading configuration. No third-party
source code is modified by this packaging step.
