import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Evidence assertions only: no runtime routing, fragment repair or publication. */
public final class Audit {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    public static void main(String[] args) throws Exception {
        Path raw=Path.of(args[0]),fixture=Path.of(args[1]);
        String a=Files.readAllLines(fixture.resolve("src/test/java/example/shared/AFirstTests.java")).stream().filter(l->l.startsWith("@SpringBootTest")).findFirst().orElseThrow();
        String b=Files.readAllLines(fixture.resolve("src/test/java/example/shared/BSecondTests.java")).stream().filter(l->l.startsWith("@SpringBootTest")).findFirst().orElseThrow();
        check(a.equals(b),"SpringBootTest configurations differ");
        var results=new TreeMap<String,Object>();String revision=null;
        for(String mode:List.of("asm-observed","asm-plain","asm-reversed","jacoco")) {
            Path dir=raw.resolve(mode);var result=new TreeMap<String,Object>();
            String actualRevision=Files.readString(dir.resolve("fixture-revision.txt")).trim();
            if(revision==null)revision=actualRevision;check(revision.equals(actualRevision),"Fixture revisions differ");
            List<String> proof=Files.readAllLines(dir.resolve("context-reuse.tsv"));check(proof.size()==2,"Two context observations required");
            List<Map<String,String>> reuse=new ArrayList<>();
            String[] first=proof.get(0).split("\t"),second=proof.get(1).split("\t");
            check(!first[0].equals(second[0]),"Same test class twice");
            for(int i:List.of(1,2,3,4))check(first[i].equals(second[i]),"Context id/object/startup/port mismatch");
            check(first[6].equals(first[0]) && second[6].equals(first[0]),"First context creator changed");
            for(String line:proof) {
                String[] p=line.split("\t");check(p[7].equals("true") && p[5].startsWith("http-nio-"),"Missing real shared-server evidence");
                reuse.add(Map.of("testClass",p[0],"contextId",p[1],"contextIdentityHash",p[2],"startupMillis",p[3],"port",p[4],"httpThread",p[5],"firstClass",p[6],"sameObjectAsserted",p[7],"directThread",p[8]));
            }
            result.put("contextReuse",reuse);result.put("fixtureRevision",revision);
            Map<String,String> tests=tests(dir.resolve("surefire"));check(tests.size()==2 && tests.values().stream().allMatch("PASS"::equals),"Fixture tests did not both pass");
            result.put("tests",tests);
            var fragment=read(dir.resolve("module/coverage-fragment-v3.json"));
            result.put("collection",fragment.get("collection"));result.put("mapped",fragment.get("tests"));result.put("setupScopes",fragment.get("setupScopes"));
            result.put("validation",read(dir.resolve("validation.json")));
            result.put("exitCodes",Files.readString(dir.resolve("exit-codes.txt")));
            if(mode.startsWith("asm")) {
                var diagnostic=read(dir.resolve("module/asm-agent-diagnostic.json"));var global=reasons();var leaf=reasons();
                var runtime=diagnostic.getAsJsonObject("runtimeEvents");count(runtime.getAsJsonArray("unattributedEvents"),global);
                for(JsonElement t:runtime.getAsJsonArray("tests"))count(t.getAsJsonObject().getAsJsonArray("unattributedEvents"),leaf);
                check(global.get("LATE_EVENT")==1 && global.values().stream().mapToLong(Long::longValue).sum()==1,"Unexpected global diagnostic counts");
                check(leaf.values().stream().mapToLong(Long::longValue).sum()==0,"Unexpected per-test diagnostics");
                result.put("globalUnattributed",global);result.put("perTestUnattributed",leaf);
                var diagnostics=new ArrayList<Map<String,String>>();
                for(JsonElement error:diagnostic.getAsJsonArray("agentErrors")) {
                    String[] p=error.getAsString().split(":",5);check(p.length==5 && p[0].equals("setup"),"Unexpected agent error");
                    diagnostics.add(Map.of("kind",p[1],"severity",p[2],"method",p[3],"detail",p[4]));
                }
                check(diagnostics.size()==1 && diagnostics.get(0).get("kind").equals("ASYNC_SETUP_UNSUPPORTED") && diagnostics.get(0).get("severity").equals("ERROR"),"Missing ERROR setup diagnostic");
                result.put("setupDiagnostics",diagnostics);result.put("metrics",diagnostic.get("metrics"));
                check(!fragment.getAsJsonObject("collection").get("completed").getAsBoolean(),"ASM unexpectedly completed");
                check(!Files.exists(dir.resolve("candidate-map.json")),"Rejected map became a candidate");
            } else {
                check(fragment.getAsJsonObject("collection").get("completed").getAsBoolean(),"JaCoCo incomplete");
                check(Files.exists(dir.resolve("candidate-map.json")),"JaCoCo join missing");
                result.put("asmDiagnostics","NOT APPLICABLE: ASM agent is not attached");
            }
            if(Files.exists(dir.resolve("context.tsv"))) {
                var http=new ArrayList<Map<String,String>>();var direct=new ArrayList<Map<String,String>>();var capture=new ArrayList<String>();var observedDiagnostics=new ArrayList<String>();
                for(String line:Files.readAllLines(dir.resolve("context.tsv")).subList(1,Files.readAllLines(dir.resolve("context.tsv")).size())) {
                    String[] p=line.split("\t",9);check(p.length==9,"Malformed observer row");check(!p[1].equals("ERROR"),"Observer failed");
                    if(p[1].equals("DIAGNOSTIC"))observedDiagnostics.add(line);
                    if(p[1].equals("CAPTURE") && p[8].contains("NioEndpoint.startInternal"))capture.add(line);
                    if(p[1].equals("HIT") && p[8].equals("example.shared.PingController#ping()Ljava/lang/String;")) {
                        var hit=Map.of("sequence",p[0],"thread",p[2],"testInterval",p[3],"activeTest",p[4],"capturedContainer",p[5],"currentContainer",p[6],"capturedActive",p[7]);
                        if(p[2].startsWith("http-nio-"))http.add(hit);else direct.add(hit);
                    }
                }
                check(http.size()==2 && direct.size()==2,"Missing HTTP or positive-control observations");
                check(http.get(0).get("capturedContainer").equals(first[0]) && http.get(1).get("capturedContainer").equals(first[0]),"Worker owner changed");
                check(http.get(0).get("capturedActive").equals("true") && http.get(1).get("capturedActive").equals("false"),"Missing open/closed owner evidence");
                check(http.stream().allMatch(h->h.get("activeTest").equals("none")),"Unexpected HTTP leaf propagation");
                check(direct.stream().allMatch(h->h.get("activeTest").equals(h.get("testInterval")) && h.get("thread").equals("main")),"Positive control failed");
                check(observedDiagnostics.size()==1 && !capture.isEmpty(),"Missing setup or startup capture trace");
                result.put("httpHits",http);result.put("positiveControls",direct);result.put("observedSetupDiagnostics",observedDiagnostics);result.put("startupCaptures",capture);
            }
            results.put(mode,result);
        }
        Files.writeString(raw.resolve("summary.json"),JSON.toJson(Map.of("identicalAnnotation",a,"runs",results))+"\n");
        System.out.println("PASS measurement: shared context proven in all four runs; ASM fails with/without observation and in reversed order; JaCoCo succeeds; positive controls pass");
    }
    static TreeMap<String,Long> reasons(){var r=new TreeMap<String,Long>();for(String s:List.of("NO_ACTIVE_TEST","LATE_EVENT","UNKNOWN_CONTEXT","TEST_ALREADY_FINISHED","STARTUP","SHUTDOWN","TRANSFORMATION_ERROR"))r.put(s,0L);return r;}
    static void count(JsonArray events,Map<String,Long> totals){for(JsonElement e:events){var o=e.getAsJsonObject();totals.merge(o.get("reason").getAsString(),o.get("count").getAsLong(),Long::sum);}}
    static Map<String,String> tests(Path path) throws Exception {
        var result=new TreeMap<String,String>();var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var files=Files.list(path)){for(Path f:files.filter(p->p.getFileName().toString().startsWith("TEST-") && p.toString().endsWith(".xml")).toList()){
            var cases=factory.newDocumentBuilder().parse(f.toFile()).getElementsByTagName("testcase");
            for(int i=0;i<cases.getLength();i++){Element t=(Element)cases.item(i);String id=t.getAttribute("classname")+"#"+t.getAttribute("name");String status=(t.getElementsByTagName("failure").getLength()+t.getElementsByTagName("error").getLength()+t.getElementsByTagName("skipped").getLength())==0?"PASS":"NOT_PASS";check(result.put(id,status)==null,"Duplicate test");}
        }}return result;
    }
    static JsonObject read(Path p) throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    static void check(boolean ok,String message){if(!ok)throw new IllegalStateException(message);}
}
