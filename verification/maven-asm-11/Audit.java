import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Read-only correlation of the retained ledger, existing runtime observations and setup scopes. */
public final class Audit {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    record Row(int sequence,String event,String thread,String interval,String active,String captured,String current,String detail) {}
    public static void main(String[] args) throws Exception {
        Path previous=Path.of(args[0]),output=Path.of(args[1]);
        JsonObject original=read(previous.resolve("raw/asm/module/asm-agent-diagnostic.json"));
        JsonObject observed=read(output.resolve("observed/module/asm-agent-diagnostic.json"));
        write(output.resolve("diagnostic-audit.json"),Map.of("original",counters(original),"observed",counters(observed)));
        List<Row> rows=new ArrayList<>();
        List<String> lines=Files.readAllLines(output.resolve("observed/context.tsv"));
        String openInterval=null;
        for(String line:lines.subList(1,lines.size())) {
            String[] p=line.split("\t",8);check(p.length==8,"Malformed observation");
            Row r=new Row(Integer.parseInt(p[0]),p[1],p[2],p[3],p[4],p[5],p[6],p[7]);
            check(!r.event.equals("ERROR"),"Observer error: "+r.detail);rows.add(r);
            if(r.event.equals("BEGIN")) {check(openInterval==null,"Overlapping test intervals");openInterval=r.detail;}
            if(r.event.equals("END")) {check(r.detail.equals(openInterval),"Mismatched test interval");openInterval=null;}
        }
        check(openInterval==null,"Unfinished test interval");
        JsonObject ledger=read(previous.resolve("raw/divergence.json"));
        JsonArray scopes=read(previous.resolve("raw/asm/map.json")).getAsJsonArray("setupScopes");
        List<Object> edges=new ArrayList<>();var totals=new TreeMap<String,Integer>();
        for(JsonElement value:ledger.getAsJsonArray("divergentEdges")) {
            JsonObject e=value.getAsJsonObject();if(!e.get("classification").getAsString().equals("unexplained"))continue;
            String test=e.get("test").getAsString();String logical=test.substring(test.indexOf("::")+2);
            String container=logical.substring(0,logical.indexOf('#'));String method=e.get("method").getAsString();
            String productionClass=method.substring(0,method.indexOf('#'));
            List<Row> hits=rows.stream().filter(r->r.event.equals("HIT") && r.interval.equals(logical) && r.detail.equals(method)).toList();
            List<Row> setup=rows.stream().filter(r->r.event.equals("SETUP") && r.interval.equals(logical) && r.detail.equals(container+"|"+method)).toList();
            check(!hits.isEmpty(),"Divergent method not observed: "+test+" -> "+method);
            check(hits.stream().allMatch(r->r.active.equals("none") && r.captured.equals(container) && r.thread.startsWith("http-nio-")),"Different route: "+test+" -> "+method);
            check(hits.size()==setup.size(),"HIT/SETUP mismatch: "+method);
            List<String> protection=scopesFor(scopes,container,productionClass);
            check(!protection.isEmpty(),"Class absent from correct affected-container scope");
            edges.add(Map.of("test",test,"method",method,"hits",hits.size(),"setupWrites",setup.size(),
                "threads",hits.stream().map(Row::thread).distinct().sorted().toList(),"hitSequences",hits.stream().map(Row::sequence).toList(),
                "setupSequences",setup.stream().map(Row::sequence).toList(),"activeTest","none","capturedContainer",container,"retainedSetupScopes",protection));
            totals.merge(logical,1,Integer::sum);
        }
        check(edges.size()==74,"Expected original 74 method edges");
        List<Object> classes=new ArrayList<>();
        for(JsonElement value:ledger.getAsJsonObject("classEdges").getAsJsonArray("differences")) {
            JsonObject e=value.getAsJsonObject();String test=e.get("test").getAsString();String logical=test.substring(test.indexOf("::")+2);
            String clazz=e.get("class").getAsString();List<String> protection=scopesFor(scopes,logical.substring(0,logical.indexOf('#')),clazz);
            check(!protection.isEmpty(),"Missing setup class");classes.add(Map.of("test",test,"class",clazz,"retainedSetupScopes",protection));
        }
        check(classes.size()==20,"Expected original 20 class edges");
        write(output.resolve("edge-routing.json"),Map.of("methodEdges",edges,"byTest",totals,"classEdges",classes));
        var controls=rows.stream().filter(r->r.event.equals("HIT") && r.thread.equals("main") && r.active.equals(r.interval)
            && (r.interval.endsWith(".OwnerControllerTests#showOwner") || r.interval.endsWith(".CrashControllerTests#triggerException"))).toList();
        check(controls.stream().anyMatch(r->r.detail.contains("OwnerController#showOwner")),"No positive OwnerController control");
        check(controls.stream().anyMatch(r->r.detail.contains("CrashController#triggerException")),"No positive CrashController control");
        write(output.resolve("positive-controls.json"),controls);
        var captures=rows.stream().filter(r->r.event.equals("CAPTURE") && (r.detail.contains("NioEndpoint.startInternal") || r.detail.contains("NioEndpoint$Poller") || r.detail.contains("PetClinicConcurrencyTests.lambda" ) || r.detail.contains("PetClinicConcurrencyTests.testDuplicate"))).toList();
        write(output.resolve("capture-chain.json"),captures);
        var eventCounts=new TreeMap<String,Integer>();rows.forEach(r->eventCounts.merge(r.event,1,Integer::sum));
        check(eventCounts.get("BEGIN")==69 && eventCounts.get("END")==69,"Incomplete test intervals");
        check(eventCounts.get("HIT")==observed.getAsJsonObject("metrics").get("rawMethodHits").getAsInt(),"Missing observed runtime hits");
        long leafHits=0;for(JsonElement t:observed.getAsJsonObject("runtimeEvents").getAsJsonArray("tests"))leafHits+=t.getAsJsonObject().getAsJsonObject("metrics").get("rawMethodHits").getAsLong();
        check(eventCounts.get("HIT")==leafHits+eventCounts.get("SETUP"),"Raw hit accounting mismatch");
        write(output.resolve("observation-summary.json"),Map.of("events",eventCounts,"rows",rows.size(),"resolvedMethodEdges",edges.size(),"retainedClassEdges",classes.size(),"positiveControls",controls.size()));
        System.out.println("PASS: all 74 method edges observed on HTTP workers and written to the correct container setup; all 20 classes retained; positive controls present");
    }
    static List<String> scopesFor(JsonArray scopes,String container,String clazz) {
        var result=new ArrayList<String>();
        for(JsonElement e:scopes) {var s=e.getAsJsonObject();if(s.get("owner").getAsString().equals("maven:.") && contains(s.getAsJsonArray("affectedContainers"),container) && contains(s.getAsJsonArray("coveredClasses"),clazz))result.add(s.get("id").getAsString());}
        return result;
    }
    static boolean contains(JsonArray values,String text) {for(JsonElement v:values)if(v.getAsString().equals(text))return true;return false;}
    static Object counters(JsonObject diagnostic) {
        var global=zeroReasons();var leaf=zeroReasons();var sums=new TreeMap<String,Long>();
        JsonObject runtime=diagnostic.getAsJsonObject("runtimeEvents");
        count(runtime.getAsJsonArray("unattributedEvents"),global);
        for(JsonElement test:runtime.getAsJsonArray("tests")) {
            var t=test.getAsJsonObject();count(t.getAsJsonArray("unattributedEvents"),leaf);
            t.getAsJsonObject("metrics").entrySet().forEach(e->sums.merge(e.getKey(),e.getValue().getAsLong(),Long::sum));
        }
        return Map.of("agentMetrics",diagnostic.get("metrics"),"agentErrors",diagnostic.get("agentErrors"),
            "globalUnattributedCounts",global,"perTestUnattributedCounts",leaf,"perTestMetricSums",sums,
            "runtimeTestRecords",runtime.getAsJsonArray("tests").size(),"setupDiagnosticsSerialized",false);
    }
    static TreeMap<String,Long> zeroReasons() {
        var map=new TreeMap<String,Long>();for(String key:List.of("NO_ACTIVE_TEST","TEST_ALREADY_FINISHED","UNKNOWN_CONTEXT","STARTUP","SHUTDOWN","LATE_EVENT","TRANSFORMATION_ERROR"))map.put(key,0L);return map;
    }
    static void count(JsonArray values,Map<String,Long> counts) {
        for(JsonElement e:values){var o=e.getAsJsonObject();counts.merge(o.get("reason").getAsString(),o.get("count").getAsLong(),Long::sum);}
    }
    static JsonObject read(Path p) throws Exception {return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    static void write(Path p,Object value) throws Exception {Files.writeString(p,JSON.toJson(value)+"\n");}
    static void check(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
}
