// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import com.intuit.karate.JsonUtils;
import com.intuit.karate.Runner;
import com.intuit.karate.Results;
import com.intuit.karate.core.Feature;
import com.intuit.karate.resource.FileResource;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class KarateTestIdentityTest {
    @TempDir Path temp;
    HttpServer server;
    Path resourceDirectory;
    java.util.concurrent.ExecutorService pool;
    List<Map<String, String>> received = new CopyOnWriteArrayList<>();

    @BeforeEach void start() throws Exception {
        resourceDirectory = Path.of(getClass().getResource("/karate-config.js").toURI()).getParent().resolve("stp-identity-generated");
        Files.createDirectories(resourceDirectory);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/", exchange -> {
            Map<String, String> fields = new TreeMap<>();
            String header = exchange.getRequestHeaders().getFirst("baggage");
            if (header != null) for (String part : header.split(",")) {
                String[] pair = part.trim().split("=", 2);
                fields.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            fields.put("path", exchange.getRequestURI().getPath()); received.add(fields);
            exchange.sendResponseHeaders(200, -1); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() throws Exception {
        server.stop(0); pool.shutdownNow();
        try (var files = Files.walk(resourceDirectory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test void sanitizesDeterministicallyWithoutDestroyingUnicodeAndRejectsEmptyOrLongNames() {
        assertEquals("create-order-happy-path", KarateTestIdentity.sanitize(" Create Order - Happy Path! "));
        assertEquals("narudžbina-東京", KarateTestIdentity.sanitize("Narudžbina 東京"));
        Locale previous = Locale.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); assertEquals("internal", KarateTestIdentity.sanitize("INTERNAL")); }
        finally { Locale.setDefault(previous); }
        assertThrows(IllegalArgumentException.class, () -> KarateTestIdentity.sanitize(" ! @ "));
        assertThrows(IllegalArgumentException.class, () -> KarateTestIdentity.create("a.feature", "x".repeat(250), null));
    }

    @Test void normalizesResourcePathAndNeverHashesAnAbsoluteFilesystemPath() {
        String expected = KarateTestIdentity.create("classpath:features/order.feature", "Create order", null);
        assertEquals(expected, KarateTestIdentity.create("classpath:features/./nested/../order.feature", "Create order", null));
        assertEquals(expected, KarateTestIdentity.create("classpath:features\\order.feature", "Create order", null));
        assertTrue(expected.matches("create-order-[0-9a-f]{12}"));
        for (String path : List.of("/home/user/order.feature", "C:\\users\\order.feature", "file:/tmp/order.feature", "../order.feature"))
            assertThrows(IllegalArgumentException.class, () -> KarateTestIdentity.create(path, "Create order", null));
    }

    @Test void canonicalRowsPreserveTypesSortNestedKeysAndNormalizeFiniteNumbers() {
        Map<String,Object> a = new LinkedHashMap<>(); a.put("z", List.of(1, true, "1")); a.put("a", Map.of("b",2,"a",3.0));
        Map<String,Object> b = new LinkedHashMap<>(); b.put("a", Map.of("a",3,"b",2.0)); b.put("z", List.of(1.0,true,"1"));
        assertEquals("{\"a\":{\"a\":3,\"b\":2},\"z\":[1,true,\"1\"]}", KarateTestIdentity.canonical(a));
        assertEquals(KarateTestIdentity.canonical(a), KarateTestIdentity.canonical(b));
        assertNotEquals(KarateTestIdentity.canonical(Map.of("a",1)), KarateTestIdentity.canonical(Map.of("a","1")));
        assertNotEquals(KarateTestIdentity.canonical(List.of(1,2)), KarateTestIdentity.canonical(List.of(2,1)));
        assertEquals("{\"a\":null}", KarateTestIdentity.canonical(Collections.singletonMap("a",null)));
        assertEquals("\"a\\u000ab\\\"\"", KarateTestIdentity.canonical("a\nb\""));
        assertThrows(IllegalArgumentException.class, () -> KarateTestIdentity.canonical(Map.of("a", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> KarateTestIdentity.canonical(Map.of("a", new Object())));
        Map<String,Object> cycle=new HashMap<>();cycle.put("cycle",cycle);
        assertThrows(IllegalArgumentException.class, () -> KarateTestIdentity.canonical(cycle));
    }

    @Test void realKarateMoveInsertBlankLinesCommentsAndFormattingKeepIdentity() throws Exception {
        String target = scenario("Create Order - Happy Path!", "target");
        String other = "Scenario: unrelated\n * def unrelated = true\n";
        String baseline = id(run("orders.feature", "Feature: identity\n" + target, true), "/target");
        for (String body : List.of(
                "Feature: identity\n\n\n# inserted comment\n" + target,
                "Feature: identity\n" + other + target,
                "Feature: identity\n" + target + other,
                "Feature: identity\n# formatting only\n" + target.replace(" Given", "   Given").replace(" When", "   When"))) {
            assertEquals(baseline, id(run("orders.feature", body, true), "/target"));
        }
        assertTrue(baseline.startsWith("create-order-happy-path-"));
    }

    @Test void realRenameAndDifferentFeatureChangeIdentity() throws Exception {
        String a=id(run("a.feature", "Feature: identity\n"+scenario("Create order","target"),true),"/target");
        String rename=id(run("a.feature", "Feature: identity\n"+scenario("Cancel order","target"),true),"/target");
        String other=id(run("b.feature", "Feature: identity\n"+scenario("Create order","target"),true),"/target");
        assertNotEquals(a,rename); assertNotEquals(a,other);
    }

    @Test void karateAllowsDuplicateNamesButStpRejectsBothExactAndSanitizedCollisions() throws Exception {
        String prefix="Feature: duplicate\n"+scenario("Create order", "a");
        assertEquals(0, run("duplicate.feature",prefix+scenario("Create order","b"),false).results.getFailCount());
        for (String duplicate:List.of("Create order", "Create---Order!")) {
            Outcome result=run("duplicate.feature",prefix+scenario(duplicate,"b"),true);
            assertTrue(result.results.getFailCount()>0);
            assertTrue(result.results.getErrorMessages().contains("Ambiguous STP Scenario"),result.results.getErrorMessages());
            assertTrue(result.wire.isEmpty());
        }
    }

    @Test void realOutlineRowAndColumnReorderingIsStableAndChangedValuesChangeId() throws Exception {
        String outline="Feature: outline\nScenario Outline: Fetch owner <owner>\n Given url '"+base()+"'\n And path '<owner>'\n When method get\n Then status 200\nExamples:\n";
        Outcome first=run("outline.feature",outline+"| owner | label |\n| 2 | second |\n| 3 | third |\n",true);
        Outcome moved=run("outline.feature",outline+"| label | owner |\n| third | 3 |\n| second | 2 |\n",true);
        assertEquals(id(first,"/2"),id(moved,"/2")); assertEquals(id(first,"/3"),id(moved,"/3"));
        assertNotEquals(id(first,"/2"),id(first,"/3"));
        assertTrue(id(first,"/2").matches("fetch-owner-owner-[0-9a-f]{12}-[0-9a-f]{12}"));
        Outcome changed=run("outline.feature",outline+"| owner | label |\n| 2 | updated |\n",true);
        assertNotEquals(id(first,"/2"),id(changed,"/2"));
    }

    @Test void resolvedTypedAndDynamicExampleDataAreAvailableAndCanonical() throws Exception {
        String outline="Feature: typed\nScenario Outline: Read example\n Given url '"+base()+"'\n And path 'target'\n When method get\n Then status 200\nExamples:\n";
        Outcome typed=run("typed.feature",outline+"| payload! |\n| { b: [1, true], a: 'two' } |\n",true);
        Outcome reordered=run("typed.feature",outline+"| payload! |\n| { a: 'two', b: [1, true] } |\n",true);
        assertEquals(id(typed,"/target"),id(reordered,"/target"));
        String dynamic="Feature: dynamic\nScenario Outline: Dynamic owner\n Given url '"+base()+"'\n And path id\n When method get\n Then status 200\nExamples:\n| [{ id: 2 }, { id: 3 }] |\n";
        Outcome result=run("dynamic.feature",dynamic,true);
        assertEquals(0,result.results.getFailCount(),result.results.getErrorMessages());
        assertNotEquals(id(result,"/2"),id(result,"/3"));
    }

    @Test void duplicateResolvedOutlineRowsFailRatherThanUseIndexes() throws Exception {
        String body="Feature: outline\nScenario Outline: repeated\n Given url '"+base()+"'\n When method get\n Then status 200\nExamples:\n| owner |\n| 2 |\n| 2 |\n";
        Outcome result=run("duplicate-outline.feature",body,true);
        assertTrue(result.results.getFailCount()>0);
        assertTrue(result.results.getErrorMessages().contains("Ambiguous STP example identity"),result.results.getErrorMessages());
        assertEquals(1,result.wire.size());
    }

    @Test void identityIsFrozenBeforeStepsMutateExampleData() throws Exception {
        String body="Feature: mutation\nScenario Outline: mutable data\n Given url '"+base()+"'\n When method get\n Then status 200\n * set __row.owner = 'changed'\n Given url '"+base()+"'\n When method get\n Then status 200\nExamples:\n| owner |\n| 2 |\n";
        Outcome result=run("mutation.feature",body,true);
        assertEquals(0,result.results.getFailCount(),result.results.getErrorMessages());
        assertEquals(2,result.wire.size());
        assertEquals(result.wire.get(0).get("stp.test.id"),result.wire.get(1).get("stp.test.id"));
        assertNotEquals(result.wire.get(0).get("stp.request.id"),result.wire.get(1).get("stp.request.id"));
    }

    private String base() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    private String scenario(String name,String path) { return "Scenario: "+name+"\n Given url '"+base()+"'\n And path '"+path+"'\n When method get\n Then status 200\n"; }
    private record Outcome(Results results,List<Map<String,String>> wire) { }
    private String id(Outcome result,String path) {
        assertEquals(0,result.results.getFailCount(),result.results.getErrorMessages());
        return result.wire.stream().filter(r->path.equals(r.get("path"))).findFirst().orElseThrow().get("stp.test.id");
    }
    @SuppressWarnings("unchecked")
    private Outcome run(String resource,String body,boolean instrumented) throws Exception {
        Path file=resourceDirectory.resolve(resource);Files.writeString(file,body);
        Feature feature=Feature.read(new FileResource(file.toFile(),true,"stp-identity-generated/"+resource));
        received.clear();
        var hook=new StpKarateHook("suite",temp.resolve("manifests"));
        var runner=Runner.builder().features(feature).outputHtmlReport(false).reportDir(temp.resolve("reports/"+UUID.randomUUID()).toString());
        if(instrumented)runner.hook(hook);
        Results result=runner.parallel(1);
        if(instrumented) {
            Map<String,Object> manifest=(Map<String,Object>)JsonUtils.fromJson(Files.readString(hook.manifestPath()));
            var rows=(List<Map<String,Object>>)manifest.get("requests");
            assertEquals(received.size(),rows.size());
            for(var row:rows) {
                var wire=received.stream().filter(r->r.get("stp.request.id").equals(row.get("requestId"))).findFirst().orElseThrow();
                assertEquals(row.get("testId"),wire.get("stp.test.id"));assertEquals("suite",wire.get("stp.test.suite.id"));
            }
        }
        return new Outcome(result,List.copyOf(received));
    }
}
