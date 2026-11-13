# Schema-v3 coverage map encoding

`ExecutableCoverageMapCodec` writes gzip files with `schemaVersion: 3` and
`encoding: "indexed-delta-1"`. This replaces the original uncompressed v3 map representation;
there is no migration reader for that experimental encoding. Schema-v1/v2 formats and the
schema-v3 **fragment and assignment** formats are unchanged. File extensions do not select
the codec; `.json.gz` is recommended for new maps.

## Dictionaries and coverage

`classes` is a strictly sorted, duplicate-free array of binary class names. `methods` is a
strictly sorted, duplicate-free array of `[classIndex, methodName, jvmDescriptor]`, ordered
by class name, method name, then descriptor. Comparisons use Java string ordering.
Indices are zero based. Method descriptors remain part of identity; overloaded methods never merge.
Setup-only classes also belong to the class dictionary.

Each property of `tests` is an executable identity, sorted by `ExecutableTestIdentity` order.
Its value contains `outcome`, `collectionStatus`, and `methods`. Method indices are sorted
and delta encoded: `[0, 4, 9]` becomes `[0, 4, 5]`. The first delta is the absolute first index;
subsequent deltas must be positive. Empty coverage uses `[]`. Out-of-range indices, negative
deltas, repeated indices and integer overflow are rejected.

The writer measures class-set equality for every test in the map:

- `classMode: "derived"`: every test's classes equal the owners of its methods. No per-test
  class array is stored; the reader derives it from the method dictionary.
- `classMode: "explicit-delta"`: at least one test has different class coverage. Every test
  then also has a `classes` array of sorted, delta-encoded class indices. This preserves valid
  class-only coverage; it does not invent method edges or discard classes.

There is no coverage-set deduplication. The observed large map had almost as many distinct
sets as tests, so an additional set dictionary would add indirection without saving space.

`unmapped` remains a sorted array of `{test, reason}` objects. It contains no class or method
references. `setupScopes` is sorted by owner then id; each scope contains `id`, `type`,
`owner`, `classes` (delta-encoded class indices), and sorted `affectedContainers` strings.
The setup-scope model carries no method coverage, so no scope method list is invented.

## Canonical stream and checksum

The top-level property order is:

```text
schemaVersion, encoding, revision, generatedAt, generator, classMode,
classes, methods, tests, unmapped, setupScopes, completeness, statistics,
lifecycleState, methodCoverage, checksum
```

`generator` contains `stpVersion`, `coverageRuntimeVersion`, `jacocoVersion`, `jdkVersion`,
including explicit nulls. Completeness retains the existing expected/reported identity and shard
sets, missing/unexpected/duplicate sets, with sorted members. Statistics and the optional
method-coverage reference retain their model meaning. Strings use Gson `JsonWriter` HTML-safe
escaping; JSON has no indentation. The same input model produces identical gzip bytes;
changing `generatedAt` necessarily changes the checksum and output.

`checksum` is `sha256:<lowercase hex>`, computed over the UTF-8 canonical object **without**
the checksum property, including the closing root brace. It is not a hash of gzip bytes.
Recompressing at another gzip level leaves this checksum unchanged. The gzip CRC/trailer
is also checked: a complete JSON object inside a truncated gzip file is not accepted.

The writer sends tokens through a buffered UTF-8 writer, `DigestOutputStream`, and
`GZIPOutputStream`. It never serializes a whole-map string or byte array. The reader uses
`GZIPInputStream` and `JsonReader`; consumed tokens are echoed to a canonical digest stream
while the domain model is built. The checksum must be the final property. Neither reader
nor writer constructs a whole-map JSON DOM. Counters for uncompressed bytes are `long`.

Dictionary objects are shared: decoded tests and setup scopes retain references to the same
class strings, and all decoded tests reuse the same `MethodIdentity` for a dictionary entry.
Joining also canonicalizes coverage objects after each fragment is decoded, before retaining
the next fragment. Fragment parsing itself uses its unchanged codec. Memory still scales
with the number of **references**; streaming does not mean the domain map is constant-size.

## Publication and selection

`join-fragments` writes a staging file beside the destination, reopens it with the streaming
reader, validates it, compares the entire model with the joined map, and loads it through
`ExplicitPrSelectorFlow.selectionMap(Path)`. Only then does an atomic move replace the
destination. Any failure deletes staging and preserves an existing map.

The CLI summary reports compressed `fileSizeBytes`, `uncompressedSizeBytes`, and the canonical
map `checksum`. The explicit-PR selector recognizes gzip by its header, reads the schema with
a streaming reader, and uses this v3 codec. Its legacy v2 load path is unchanged.
