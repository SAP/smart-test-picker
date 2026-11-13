// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.coverage.serialization;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.sap.oss.smarttestpicker.coverage.CoverageMapContract;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.validation.*;

/** Streaming, dictionary-indexed schema-v3 gzip maps. Fragment/assignment codecs are independent. */
public final class ExecutableCoverageMapCodec
{
    public static final String ENCODING = "indexed-delta-1";
    public record ReadResult(ExecutableCoverageMap map, String checksum, long uncompressedBytes) {}

    /** Writes a gzip file and returns the checksum of its canonical uncompressed body. */
    public String write(ExecutableCoverageMap map, Path path) throws IOException
    {
        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(path))) { return write(map, output); }
    }

    /** Writes and closes the supplied stream. No whole-map byte or character buffer is used. */
    public String write(ExecutableCoverageMap map, OutputStream output) throws IOException
    {
        CompactMapTables tables = new CompactMapTables(map);
        MessageDigest digest = digest();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output, 65536);
             DigestOutputStream hashing = new DigestOutputStream(gzip, digest);
             JsonWriter writer = writer(hashing))
        {
            writer.beginObject(); writeBody(writer, map, tables); writer.flush();
            // Exclude the checksum property, but include the closing root brace in the digest.
            digest.update((byte) '}');
            String checksum = checksum(digest);
            hashing.on(false);
            writer.name("checksum").value(checksum).endObject(); writer.flush();
            return checksum;
        }
    }

    public ExecutableCoverageMap read(Path path) throws IOException { return readVerified(path).map(); }
    public ReadResult readVerified(Path path) throws IOException
    {
        return readVerified(new BufferedInputStream(Files.newInputStream(path)));
    }

    /** Reads, verifies the canonical checksum and gzip trailer, and closes the supplied stream. */
    public ReadResult readVerified(InputStream input) throws IOException
    {
        MessageDigest digest = digest();
        try (InputStream owned = input;
             CountingInputStream plain = new CountingInputStream(new GZIPInputStream(owned, 65536));
             JsonReader reader = new JsonReader(new InputStreamReader(plain, StandardCharsets.UTF_8));
             DigestOutputStream canonicalDigest = new DigestOutputStream(OutputStream.nullOutputStream(), digest);
             JsonWriter canonical = writer(canonicalDigest))
        {
            CanonicalMapInput in = new CanonicalMapInput(reader, canonical);
            in.object(); in.name("schemaVersion"); int version = in.integer();
            if (version != CoverageMapContract.SCHEMA_V3)
            {
                throw invalid(ValidationCategory.INCOMPATIBLE_SCHEMA,
                        version > 3 ? ValidationCode.HIGHER_SCHEMA_VERSION : ValidationCode.SCHEMA_VERSION_MISMATCH,
                        "Unsupported executable coverage-map schema version: " + version);
            }
            in.name("encoding");
            if (!ENCODING.equals(in.string())) { throw new IllegalArgumentException("Unsupported executable coverage-map encoding"); }
            ExecutableCoverageMap map = readBody(in, version);
            in.finishCanonicalBody(); String actual = checksum(digest);
            if (!"checksum".equals(reader.nextName()) || !actual.equals(reader.nextString()))
            {
                throw invalid(ValidationCategory.CORRUPT_MAP, ValidationCode.CHECKSUM_MISMATCH, "Executable coverage-map checksum mismatch");
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) { throw new IllegalArgumentException("Trailing executable coverage-map data"); }
            validate(map).errors().stream().filter(error -> error.code() != ValidationCode.LIFECYCLE_NOT_PUBLISHED)
                    .findFirst().ifPresent(error -> { throw new CoverageMapValidationException(error); });
            return new ReadResult(map, actual, plain.count);
        }
        catch (CoverageMapValidationException e) { throw e; }
        catch (RuntimeException e)
        {
            throw invalid(ValidationCategory.INVALID_STRUCTURE, ValidationCode.MALFORMED_TEST_IDENTITY,
                    "Malformed executable coverage map: " + e.getMessage());
        }
    }

    public ValidationResult validate(ExecutableCoverageMap map) { return ExecutableCoverageMapValidator.validate(map); }
    private static JsonWriter writer(OutputStream output)
    {
        JsonWriter writer = new JsonWriter(new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8), 65536));
        writer.setHtmlSafe(true); writer.setSerializeNulls(true); return writer;
    }

    private static void writeBody(JsonWriter w, ExecutableCoverageMap map, CompactMapTables t) throws IOException
    {
        w.name("schemaVersion").value(map.schemaVersion()); w.name("encoding").value(ENCODING);
        w.name("revision").value(map.revision().value()); w.name("generatedAt").value(map.generatedAt().toString());
        w.name("generator").beginObject();
        w.name("stpVersion").value(map.generator().stpVersion());
        w.name("coverageRuntimeVersion").value(map.generator().coverageRuntimeVersion());
        w.name("jacocoVersion").value(map.generator().jacocoVersion());
        w.name("jdkVersion").value(map.generator().jdkVersion()); w.endObject();
        w.name("classMode").value(t.derivedClasses ? "derived" : "explicit-delta");
        w.name("classes"); strings(w, t.classes);
        w.name("methods").beginArray();
        for (MethodIdentity method : t.methods)
        {
            w.beginArray().value(t.classIds.get(method.binaryClassName())).value(method.methodName())
                    .value(method.jvmDescriptor()).endArray();
        }
        w.endArray(); w.name("tests").beginObject();
        for (var entry : new TreeMap<>(map.tests()).entrySet())
        {
            TestCoverage coverage = entry.getValue();
            w.name(entry.getKey().toString()).beginObject();
            w.name("outcome").value(coverage.outcome().name());
            w.name("collectionStatus").value(coverage.collectionStatus().name());
            if (!t.derivedClasses) { w.name("classes"); MapIndexDeltas.write(w, coverage.coveredClasses(), t.classIds); }
            w.name("methods"); MapIndexDeltas.write(w, coverage.coveredMethods(), t.methodIds); w.endObject();
        }
        w.endObject(); w.name("unmapped").beginArray();
        for (var value : map.unmapped())
        {
            w.beginObject().name("test").value(value.test().toString()).name("reason").value(value.reason().name()).endObject();
        }
        w.endArray(); w.name("setupScopes").beginArray();
        for (SetupScope scope : map.setupScopes().stream().sorted(Comparator
                .comparing((SetupScope s) -> s.owner().toString()).thenComparing(SetupScope::id)).toList())
        {
            w.beginObject().name("id").value(scope.id()).name("type").value(scope.type().name());
            w.name("owner").value(scope.owner().toString());
            w.name("classes"); MapIndexDeltas.write(w, scope.coveredClasses(), t.classIds);
            w.name("affectedContainers"); strings(w, scope.affectedContainers().stream().map(TestContainer::binaryName).sorted().toList());
            w.endObject();
        }
        w.endArray(); w.name("completeness"); writeCompleteness(w, map.completeness());
        w.name("statistics");
        if (map.statistics() == null) { w.nullValue(); }
        else
        {
            MapStatistics s = map.statistics();
            w.beginObject().name("expectedTests").value(s.expectedTests()).name("mappedTests").value(s.mappedTests());
            w.name("unmappedTests").value(s.unmappedTests()).name("setupScopes").value(s.setupScopes());
            w.name("classEdges").value(s.classEdges()).name("methodEdges").value(s.methodEdges()).endObject();
        }
        w.name("lifecycleState").value(map.lifecycleState().name()); w.name("methodCoverage");
        if (map.methodCoverageReference() == null) { w.nullValue(); }
        else
        {
            w.beginObject().name("artifact").value(map.methodCoverageReference().artifact())
                    .name("sha256").value(map.methodCoverageReference().sha256()).endObject();
        }
    }

    private static void strings(JsonWriter w, Collection<String> values) throws IOException
    {
        w.beginArray(); for (String value : values) { w.value(value); } w.endArray();
    }
    private static void writeCompleteness(JsonWriter w, ExecutableCompleteness c) throws IOException
    {
        if (c == null) { w.nullValue(); return; }
        w.beginObject();
        w.name("expectedTests"); identities(w, c.expectedTests()); w.name("reportedTests"); identities(w, c.reportedTests());
        w.name("expectedShards"); shards(w, c.expectedShards()); w.name("completedShards"); shards(w, c.completedShards());
        w.name("missingTests"); identities(w, c.missingTests()); w.name("unexpectedTests"); identities(w, c.unexpectedTests());
        w.name("missingShards"); shards(w, c.missingShards()); w.name("duplicateShards"); shards(w, c.duplicateShards());
        w.name("duplicateTests"); identities(w, c.duplicateTests()); w.endObject();
    }
    private static void identities(JsonWriter w, Set<ExecutableTestIdentity> values) throws IOException
    {
        w.beginArray(); for (ExecutableTestIdentity value : new TreeSet<>(values)) { w.value(value.toString()); } w.endArray();
    }
    private static void shards(JsonWriter w, Set<ShardId> values) throws IOException
    {
        strings(w, values.stream().sorted().map(ShardId::value).toList());
    }

    private static ExecutableCoverageMap readBody(CanonicalMapInput in, int version) throws IOException
    {
        IdentityPool pool = new IdentityPool();
        in.name("revision"); CoverageMapRevision revision = new CoverageMapRevision(in.string());
        in.name("generatedAt"); Instant generated = Instant.parse(in.string());
        in.name("generator"); in.object();
        in.name("stpVersion"); String stp = in.nullableString();
        in.name("coverageRuntimeVersion"); String runtime = in.nullableString();
        in.name("jacocoVersion"); String jacoco = in.nullableString();
        in.name("jdkVersion"); String jdk = in.nullableString(); in.endObject();
        in.name("classMode"); String mode = in.string();
        if (!Set.of("derived", "explicit-delta").contains(mode)) { throw new IllegalArgumentException("Unknown classMode: " + mode); }
        in.name("classes"); in.array(); List<String> classes = new ArrayList<>();
        while (in.more())
        {
            String value = pool.string(in.string());
            if (!classes.isEmpty() && classes.get(classes.size() - 1).compareTo(value) >= 0)
            { throw new IllegalArgumentException("Class table must be strictly sorted"); }
            classes.add(value);
        }
        in.endArray(); in.name("methods"); in.array(); List<MethodIdentity> methods = new ArrayList<>();
        while (in.more())
        {
            in.array(); String owner = indexed(classes, in.integer());
            MethodIdentity value = new MethodIdentity(owner, pool.string(in.string()), pool.string(in.string())); in.endArray();
            if (!methods.isEmpty() && CompactMapTables.METHOD_ORDER.compare(methods.get(methods.size() - 1), value) >= 0)
            { throw new IllegalArgumentException("Method table must be strictly sorted"); }
            methods.add(value);
        }
        in.endArray(); in.name("tests"); in.object();
        Map<ExecutableTestIdentity, TestCoverage> tests = new TreeMap<>(); ExecutableTestIdentity previous = null;
        while (in.more())
        {
            ExecutableTestIdentity id = pool.identity(in.name());
            if (previous != null && previous.compareTo(id) >= 0) { throw new IllegalArgumentException("Duplicate or unsorted executable identity: " + id); }
            previous = id; in.object();
            in.name("outcome"); TestOutcome outcome = TestOutcome.valueOf(in.string());
            in.name("collectionStatus"); CollectionStatus status = CollectionStatus.valueOf(in.string());
            Set<String> coveredClasses = new HashSet<>();
            if (mode.equals("explicit-delta")) { in.name("classes"); coveredClasses = deltas(in, classes); }
            in.name("methods"); Set<MethodIdentity> coveredMethods = deltas(in, methods);
            if (mode.equals("derived"))
            {
                for (MethodIdentity method : coveredMethods) { coveredClasses.add(method.binaryClassName()); }
            }
            in.endObject(); tests.put(id, new TestCoverage(coveredClasses, coveredMethods, outcome, status));
        }
        in.endObject(); in.name("unmapped"); in.array(); List<ExecutableUnmappedTest> unmapped = new ArrayList<>();
        while (in.more())
        {
            in.object(); in.name("test"); ExecutableTestIdentity id = pool.identity(in.string());
            in.name("reason"); UnmappedReason reason = UnmappedReason.valueOf(in.string()); in.endObject();
            unmapped.add(new ExecutableUnmappedTest(id, reason));
        }
        in.endArray(); in.name("setupScopes"); in.array(); List<SetupScope> scopes = new ArrayList<>();
        while (in.more())
        {
            in.object(); in.name("id"); String id = in.string();
            in.name("type"); SetupScopeType type = SetupScopeType.valueOf(in.string());
            in.name("owner"); ExecutionTarget owner = ExecutionTarget.parse(in.string());
            in.name("classes"); Set<String> covered = deltas(in, classes);
            in.name("affectedContainers"); Set<TestContainer> containers = values(in, s -> new TestContainer(pool.string(s)));
            in.endObject(); scopes.add(new SetupScope(id, type, covered, containers, owner));
        }
        in.endArray(); in.name("completeness"); ExecutableCompleteness completeness = readCompleteness(in, pool);
        in.name("statistics"); MapStatistics statistics = null;
        if (!in.nil())
        {
            in.object(); in.name("expectedTests"); int expected = in.integer();
            in.name("mappedTests"); int mapped = in.integer(); in.name("unmappedTests"); int unknown = in.integer();
            in.name("setupScopes"); int setup = in.integer(); in.name("classEdges"); long classEdges = in.number();
            in.name("methodEdges"); long methodEdges = in.number(); in.endObject();
            statistics = new MapStatistics(expected, mapped, unknown, setup, classEdges, methodEdges);
        }
        in.name("lifecycleState"); CoverageMapLifecycleState lifecycle = CoverageMapLifecycleState.valueOf(in.string());
        in.name("methodCoverage"); MethodCoverageReference reference = null;
        if (!in.nil())
        {
            in.object(); in.name("artifact"); String artifact = in.string();
            in.name("sha256"); reference = new MethodCoverageReference(artifact, in.string()); in.endObject();
        }
        return new ExecutableCoverageMap(version, revision, generated, new GeneratorProvenance(stp, runtime, jacoco, jdk),
                tests, unmapped, scopes, completeness, statistics, lifecycle, reference);
    }

    private static ExecutableCompleteness readCompleteness(CanonicalMapInput in, IdentityPool pool) throws IOException
    {
        if (in.nil()) { return null; }
        in.object(); in.name("expectedTests"); Set<ExecutableTestIdentity> expected = values(in, pool::identity);
        in.name("reportedTests"); Set<ExecutableTestIdentity> reported = values(in, pool::identity);
        in.name("expectedShards"); Set<ShardId> expectedShards = values(in, ShardId::new);
        in.name("completedShards"); Set<ShardId> completed = values(in, ShardId::new);
        in.name("missingTests"); Set<ExecutableTestIdentity> missing = values(in, pool::identity);
        in.name("unexpectedTests"); Set<ExecutableTestIdentity> unexpected = values(in, pool::identity);
        in.name("missingShards"); Set<ShardId> missingShards = values(in, ShardId::new);
        in.name("duplicateShards"); Set<ShardId> duplicateShards = values(in, ShardId::new);
        in.name("duplicateTests"); Set<ExecutableTestIdentity> duplicateTests = values(in, pool::identity); in.endObject();
        return new ExecutableCompleteness(expected, reported, expectedShards, completed, missing, unexpected,
                missingShards, duplicateShards, duplicateTests);
    }
    private static <T> Set<T> values(CanonicalMapInput in, Function<String, T> parse) throws IOException
    {
        Set<T> result = new HashSet<>(); in.array();
        while (in.more())
        {
            if (!result.add(parse.apply(in.string()))) { throw new IllegalArgumentException("Duplicate map set entry"); }
        }
        in.endArray(); return result;
    }
    private static <T> Set<T> deltas(CanonicalMapInput in, List<T> table) throws IOException
    {
        Set<T> result = new HashSet<>(); in.array(); int previous = 0; boolean first = true;
        while (in.more())
        {
            int index = MapIndexDeltas.resolve(in.integer(), previous, first, table.size());
            result.add(table.get(index)); previous = index; first = false;
        }
        in.endArray(); return result;
    }
    private static <T> T indexed(List<T> table, int index)
    {
        if (index < 0 || index >= table.size()) { throw new IllegalArgumentException("Map table index out of range: " + index); }
        return table.get(index);
    }
    private static MessageDigest digest()
    {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String checksum(MessageDigest digest) { return "sha256:" + HexFormat.of().formatHex(digest.digest()); }
    private static CoverageMapValidationException invalid(ValidationCategory category, ValidationCode code, String message)
    {
        return new CoverageMapValidationException(ExecutableCoverageMapValidator.error(category, code, message));
    }
    private static final class IdentityPool
    {
        private final Map<String, String> strings = new HashMap<>();
        private final Map<ExecutableTestIdentity, ExecutableTestIdentity> identities = new HashMap<>();
        String string(String value) { return strings.computeIfAbsent(value, key -> key); }
        ExecutableTestIdentity identity(String wire)
        {
            ExecutableTestIdentity parsed = ExecutableTestIdentity.parse(wire); TestIdentity logical = parsed.test();
            ExecutableTestIdentity value = new ExecutableTestIdentity(parsed.target(), new TestIdentity(
                    string(logical.className()), string(logical.methodName()), string(logical.methodParameterTypes())));
            return identities.computeIfAbsent(value, key -> key);
        }
    }
    private static final class CountingInputStream extends FilterInputStream
    {
        long count;
        CountingInputStream(InputStream input) { super(input); }
        @Override public int read() throws IOException
        {
            int value = in.read(); if (value >= 0) { count++; } return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException
        {
            int n = in.read(bytes, offset, length); if (n > 0) { count += n; } return n;
        }
    }
}
