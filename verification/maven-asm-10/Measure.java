package com.sap.oss.smarttestpicker.maven;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import com.google.gson.*;
import com.sap.oss.smarttestpicker.coverage.ExecutableCoverageFragmentJoiner;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.*;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;

/** Measurement harness only; all inventory/fragment/evidence contracts come from production. */
public class Measure {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "configure" -> configure(Path.of(args[1]));
            case "assign" -> {
                var root = Path.of(args[1]);
                var inventory = new ExecutableHeadTestInventoryCodec().read(root.resolve(".stp/inventory.json").toFile());
                Files.write(root.resolve(".stp/assignment.json"), new ExecutableShardAssignmentCodec().serialize(
                    new ExecutableShardAssignment(1, new CoverageMapRevision(inventory.revision()), new ShardId("local"), inventory.runnableTests())));
                System.out.println("Assigned " + inventory.runnableTests().size() + " discovered identities");
            }
            case "verify" -> verify(Path.of(args[1]), Path.of(args[2]));
            case "counters" -> compareCounters(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            case "divergence" -> divergence(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            case "files" -> files(Path.of(args[1]), Path.of(args[2]));
            case "results" -> compareResults(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
            case "summary" -> summary(Path.of(args[1]), Path.of(args[2]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    static void configure(Path root) throws Exception {
        // A separate POM adds build-only measurement profiles; upstream sources and pom.xml stay intact.
        String profiles = """
        <profile><id>measurement</id><build><plugins><plugin>
          <artifactId>maven-surefire-plugin</artifactId><configuration>
            <forkCount>1</forkCount><reuseForks>true</reuseForks><runOrder>alphabetical</runOrder>
            <argLine>@{argLine} -Xmx768m</argLine>
            <excludes><exclude>**/MySqlIntegrationTests.java</exclude><exclude>**/PostgresIntegrationTests.java</exclude></excludes>
          </configuration>
        </plugin></plugins></build></profile>
        <profile><id>stp-measurement</id>
          <dependencies><dependency><groupId>com.sap.oss.smart-test-picker</groupId>
            <artifactId>smart-test-picker-core</artifactId><version>0.3.0-SNAPSHOT</version><scope>test</scope>
          </dependency></dependencies>
          <build><plugins><plugin><groupId>com.sap.oss.smart-test-picker</groupId>
            <artifactId>smart-test-picker-maven</artifactId><version>0.3.0-SNAPSHOT</version>
            <configuration>
              <moduleFragmentOutput>${smartTestPicker.moduleFragmentOutput}</moduleFragmentOutput>
              <moduleEvidenceOutput>${smartTestPicker.moduleEvidenceOutput}</moduleEvidenceOutput>
            </configuration>
            <executions><execution><id>stp-reports</id><phase>verify</phase>
              <goals><goal>generate-reports</goal></goals>
            </execution><execution><id>stp-fragment</id><phase>verify</phase>
              <goals><goal>generate-coverage-fragment</goal></goals>
            </execution></executions>
          </plugin></plugins></build>
        </profile>
        """;
        String pom = Files.readString(root.resolve("pom.xml"));
        if (!pom.contains("</profiles>")) throw new IllegalStateException("Subject profiles missing");
        Files.writeString(root.resolve("measurement-pom.xml"), pom.replace("</profiles>", profiles + "</profiles>"));
    }

    static void verify(Path root, Path output) throws Exception {
        Files.createDirectories(output);
        var assignment = new ExecutableShardAssignmentCodec().deserialize(Files.readAllBytes(root.resolve(".stp/assignment.json")));
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(root.resolve(".stp/aggregate.json")));
        var evidence = MavenMappingEvidence.read(root.resolve(".stp/evidence.json"));
        var moduleEvidence = MavenMappingEvidence.read(root.resolve("target/stp/execution-evidence-v2.json"));
        check(evidence.revision().equals(assignment.revision().value()), "evidence revision");
        check(evidence.shardId().equals(assignment.shardId().value()), "evidence shard");
        check(fragment.collectionCompleted(), "fragment incomplete");
        Set<ExecutableTestIdentity> owners = MavenMappingEvidence.coverageOwners(fragment);
        check(owners.equals(evidence.executed()), "coverage owners differ from EXECUTED");
        check(Collections.disjoint(owners, evidence.nonExecuted()), "execution overlap");
        Set<ExecutableTestIdentity> all = new TreeSet<>(owners); all.addAll(evidence.nonExecuted());
        check(all.equals(assignment.tests()), "assignment is not exactly accounted");
        check(moduleEvidence.executed().equals(evidence.executed()) && moduleEvidence.nonExecuted().equals(evidence.nonExecuted()), "module/aggregate evidence");
        var inventory = new ExecutableHeadTestInventoryCodec().read(root.resolve(".stp/inventory.json").toFile());
        check(inventory.runnableTests().equals(assignment.tests()), "inventory/assignment");
        var plan = new ExecutableShardPlan(assignment.revision(), Map.of(assignment.shardId(), assignment.tests()));
        var map = new ExecutableCoverageFragmentJoiner().join(
            ExecutableTestInventory.from(assignment.revision(), assignment.tests()), plan, List.of(fragment),
            evidence.nonExecuted(), Instant.EPOCH, new GeneratorProvenance("0.3.0-SNAPSHOT", "ASM10", "project", System.getProperty("java.version")));
        check(map.completeness().isComplete(), "incomplete joined map");
        Files.write(output.resolve("map.json"), new ExecutableCoverageMapCodec().serialize(map));
        var outcomes = new TreeMap<String,String>();
        fragment.tests().forEach((id,c) -> outcomes.put(id.toString(), c.outcome() + "/" + c.collectionStatus()));
        fragment.unmapped().forEach(u -> outcomes.put(u.test().toString(), "UNMAPPED/" + u.reason()));
        evidence.nonExecuted().forEach(id -> outcomes.put(id.toString(), "NON_EXECUTED"));
        write(output.resolve("accounting.json"), Map.of("assignment", strings(assignment.tests()),
            "executed", strings(evidence.executed()), "positivelyNonExecuted", strings(evidence.nonExecuted()),
            "outcomes", outcomes, "statistics", map.statistics(), "complete", true));
        System.out.println("PRODUCTION CODECS PASS assignment=" + all.size() + " mapped=" + map.tests().size()
            + " executableUnmapped=" + map.unmapped().size() + " positiveNonExecution=" + evidence.nonExecuted().size());
    }

    static Map<String,Map<String,List<Integer>>> counters(Path path) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        var doc = factory.newDocumentBuilder().parse(path.toFile());
        var result = new TreeMap<String,Map<String,List<Integer>>>();
        var classes = doc.getElementsByTagName("class");
        for (int i=0;i<classes.getLength();i++) {
            Element c=(Element)classes.item(i); String name=c.getAttribute("name");
            result.put(name, counterChildren(c));
            for (Element m : children(c,"method")) result.put(name+"#"+m.getAttribute("name")+m.getAttribute("desc"),counterChildren(m));
        }
        return result;
    }
    static Map<String,List<Integer>> counterChildren(Element e) {
        var result=new TreeMap<String,List<Integer>>();
        for (Element c:children(e,"counter")) result.put(c.getAttribute("type"),List.of(Integer.parseInt(c.getAttribute("missed")),Integer.parseInt(c.getAttribute("covered"))));
        return result;
    }
    static List<Element> children(Element e,String name) {
        var result=new ArrayList<Element>();
        for(Node n=e.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element c && c.getTagName().equals(name)) result.add(c);
        return result;
    }
    static void compareCounters(Path baseline, Path coexist, Path out) throws Exception {
        var a=counters(baseline); var b=counters(coexist); var changes=new TreeMap<String,Object>();
        var keys=new TreeSet<>(a.keySet()); keys.addAll(b.keySet());
        for(String k:keys) if(!Objects.equals(a.get(k),b.get(k))) {
            var values=new TreeMap<String,Object>(); values.put("baseline",a.get(k));values.put("coexistence",b.get(k)); changes.put(k,values);
        }
        write(out,Map.of("baseline",a,"coexistence",b,"differences",changes));
        System.out.println("PROJECT JACOCO COUNTERS entries="+keys.size()+" differences="+changes.size());
        check(changes.isEmpty(),"Project JaCoCo counters differ; no mitigation permitted");
    }
    static Map<String,String> results(Path directory) throws Exception {
        var result=new TreeMap<String,String>();
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var files=Files.list(directory)) {
            for(Path p:files.filter(f->f.getFileName().toString().startsWith("TEST-")).toList()) {
                var tests=factory.newDocumentBuilder().parse(p.toFile()).getElementsByTagName("testcase");
                for(int i=0;i<tests.getLength();i++) {
                    Element t=(Element)tests.item(i);
                    String id=t.getAttribute("classname")+"#"+t.getAttribute("name");
                    String outcome=!children(t,"failure").isEmpty()?"FAILED":!children(t,"error").isEmpty()?"ERROR":!children(t,"skipped").isEmpty()?"SKIPPED":"PASS";
                    check(result.put(id,outcome)==null,"Duplicate XML test case: "+id);
                }
            }
        }
        return result;
    }
    static void compareResults(Path a,Path b,Path out) throws Exception {
        var left=results(a);var right=results(b);
        write(out,Map.of("left",left,"right",right,"equal",left.equals(right)));
        check(!left.isEmpty() && left.equals(right),"Test identities or outcomes differ");
        check(left.values().stream().allMatch("PASS"::equals),"Nonpassing test");
        System.out.println("EXACT XML IDENTITIES AND OUTCOMES PASS tests="+left.size());
    }
    static void divergence(Path asmPath,Path jacocoPath,Path out) throws Exception {
        var codec=new ExecutableCoverageMapCodec();
        var a=codec.deserialize(Files.readAllBytes(asmPath)); var b=codec.deserialize(Files.readAllBytes(jacocoPath));
        check(a.revision().equals(b.revision()),"map revisions differ");
        check(a.completeness().expectedTests().equals(b.completeness().expectedTests()),"map populations differ");
        Set<String> left=edges(a),right=edges(b); var rows=new ArrayList<Map<String,String>>();
        for (String edge:left) if(!right.contains(edge)) rows.add(edgeRow(edge,"ASM_ONLY"));
        for (String edge:right) if(!left.contains(edge)) rows.add(edgeRow(edge,"JACOCO_ONLY"));
        var counts=new TreeMap<String,Map<String,Integer>>();
        for(var row:rows) {
            String type=row.get("method").split("#",2)[0];
            counts.computeIfAbsent(type,k->new TreeMap<>()).merge(row.get("classification"),1,Integer::sum);
        }
        var globalA=new TreeSet<String>();var globalB=new TreeSet<String>();
        left.forEach(e->globalA.add(e.split("\t",2)[1]));right.forEach(e->globalB.add(e.split("\t",2)[1]));
        var onlyA=new TreeSet<>(globalA);onlyA.removeAll(globalB);
        var onlyB=new TreeSet<>(globalB);onlyB.removeAll(globalA);
        var classesA=classEdges(a);var classesB=classEdges(b);
        var classRows=new ArrayList<Map<String,String>>();
        for(String e:classesA) if(!classesB.contains(e)) classRows.add(classRow(e,"ASM_ONLY"));
        for(String e:classesB) if(!classesA.contains(e)) classRows.add(classRow(e,"JACOCO_ONLY"));
        write(out,Map.of("asmMethodEdges",left.size(),"jacocoMethodEdges",right.size(),"divergentEdges",rows,
            "countsByProductionClass",counts,"globalMethods",Map.of("asm",globalA.size(),"jacoco",globalB.size(),
                "asmOnly",onlyA,"jacocoOnly",onlyB),"classEdges",Map.of("asm",classesA.size(),"jacoco",classesB.size(),"differences",classRows)));
        System.out.println("DIVERGENCE ASM="+left.size()+" JACOCO="+right.size()+" symmetricDifference="+rows.size());
    }
    static Set<String> edges(ExecutableCoverageMap map) {
        var result=new TreeSet<String>();
        map.tests().forEach((test,coverage)->coverage.coveredMethods().forEach(m->result.add(test+"\t"+m)));
        return result;
    }
    static Set<String> classEdges(ExecutableCoverageMap map) {
        var result=new TreeSet<String>();
        map.tests().forEach((test,coverage)->coverage.coveredClasses().forEach(c->result.add(test+"\t"+c)));
        return result;
    }
    static Map<String,String> classRow(String edge,String side) {
        String[] pair=edge.split("\t",2);
        return Map.of("test",pair[0],"class",pair[1],"side",side,"classification","unexplained");
    }
    static Map<String,String> edgeRow(String edge,String side) {
        String[] pair=edge.split("\t",2);
        // These two exact generated bridges were verified by javap flags and the JaCoCo filter bytecode.
        // This labels retained evidence; it never filters or changes either map.
        if(side.equals("ASM_ONLY") && Set.of(
            "org.springframework.samples.petclinic.owner.PetTypeFormatter#parse(Ljava/lang/String;Ljava/util/Locale;)Ljava/lang/Object;",
            "org.springframework.samples.petclinic.owner.PetTypeFormatter#print(Ljava/lang/Object;Ljava/util/Locale;)Ljava/lang/String;"
        ).contains(pair[1])) return Map.of("test",pair[0],"method",pair[1],"side",side,
            "classification","additional-finding-generated-bridge-filtering",
            "evidence","formatter-and-repository-bytecode.txt; jacoco-synthetic-filter-bytecode.txt; per-test JaCoCo XML");
        // No exact method-level causal attribution is guessed from class overlap in setup scopes.
        return Map.of("test",pair[0],"method",pair[1],"side",side,"classification","unexplained");
    }
    static void files(Path root, Path out) throws Exception {
        var rows=new TreeMap<String,Object>();
        try(var paths=Files.walk(root.resolve("target"))) {
            for(Path p:paths.filter(Files::isRegularFile).toList()) {
                String name=root.relativize(p).toString();
                if(name.endsWith(".exec") || name.endsWith(".identity") || name.endsWith(".non-executed")
                    || name.contains("jacoco-xml/") || name.startsWith("target/stp/") || name.endsWith(".class")) {
                    String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
                    rows.put(name,Map.of("bytes",Files.size(p),"sha256",hash));
                }
            }
        }
        write(out,rows);
    }
    static void summary(Path repo,Path raw) throws Exception {
        var suites=new TreeMap<String,Object>();
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        for(String module:List.of("smart-test-picker-common","smart-test-picker-core","smart-test-picker-maven","smart-test-picker","stp-agent")) {
            var totals=new TreeMap<String,Integer>();
            try(var paths=Files.list(repo.resolve(module+"/build/test-results/test"))) {
                for(Path p:paths.filter(f->f.getFileName().toString().startsWith("TEST-")).toList()) {
                    Element root=factory.newDocumentBuilder().parse(p.toFile()).getDocumentElement();
                    for(String key:List.of("tests","failures","errors","skipped")) totals.merge(key,Integer.parseInt(root.getAttribute(key)),Integer::sum);
                }
            }
            check(totals.getOrDefault("tests",0)>0 && totals.get("failures")==0 && totals.get("errors")==0,"Suite failed: "+module);
            suites.put(module,totals);
        }
        write(raw.resolve("suite-counts.json"),suites);
        var costs=new TreeMap<String,Object>();
        for(String mode:List.of("asm","jacoco","baseline","coexist")) {
            Path dir=raw.resolve(mode);
            JsonObject files=JsonParser.parseString(Files.readString(dir.resolve("collection-files.json"))).getAsJsonObject();
            int xmlCount=0;long xmlBytes=0;int execCount=0;long execBytes=0;
            for(var e:files.entrySet()) {
                if(e.getKey().startsWith("target/jacoco-xml/") && e.getKey().endsWith(".xml")) {xmlCount++;xmlBytes+=e.getValue().getAsJsonObject().get("bytes").getAsLong();}
                if(e.getKey().endsWith(".exec") && !e.getKey().equals("target/jacoco.exec")) {execCount++;execBytes+=e.getValue().getAsJsonObject().get("bytes").getAsLong();}
            }
            var values=new TreeMap<String,Object>();
            for(String line:Files.readAllLines(dir.resolve("time.txt"))) {
                if(line.contains(" real ")) values.put("wallSeconds",Double.parseDouble(line.trim().split("\\s+")[0]));
                if(line.contains("maximum resident set size")) values.put("maxRssBytes",Long.parseLong(line.trim().split("\\s+")[0]));
            }
            values.put("perTestXmlFiles",xmlCount);values.put("perTestXmlBytes",xmlBytes);
            values.put("mappingExecFiles",execCount);values.put("mappingExecBytes",execBytes);
            if(Files.exists(dir.resolve("map.json"))) values.put("mapBytes",Files.size(dir.resolve("map.json")));
            costs.put(mode,values);
        }
        write(raw.resolve("costs.json"),costs);
        var baseline=classHashes(raw.resolve("baseline/collection-files.json"));
        var equality=new TreeMap<String,Object>();
        for(String mode:List.of("asm","jacoco","coexist")) {
            var actual=classHashes(raw.resolve(mode+"/collection-files.json"));
            check(baseline.equals(actual),"Compiled subject bytes differ: "+mode);
            equality.put(mode,Map.of("classFiles",actual.size(),"equalToBaseline",true));
        }
        write(raw.resolve("compiled-class-comparison.json"),equality);
        System.out.println("Suite, cost and compiled-class evidence written; all subject class hashes match baseline");
    }
    static Map<String,String> classHashes(Path path) throws Exception {
        var result=new TreeMap<String,String>();
        JsonParser.parseString(Files.readString(path)).getAsJsonObject().entrySet().forEach(e->{
            if(e.getKey().endsWith(".class")) result.put(e.getKey(),e.getValue().getAsJsonObject().get("sha256").getAsString());
        });
        check(!result.isEmpty(),"No compiled classes");return result;
    }
    static List<String> strings(Collection<?> values) {return values.stream().map(Object::toString).sorted().toList();}
    static void check(boolean ok,String message) {if(!ok) throw new IllegalStateException(message);}
    static void write(Path out,Object value) throws Exception {Files.writeString(out,JSON.toJson(value)+"\n");}
}
