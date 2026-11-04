import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Assertions over retained evidence, not a replacement for production fragment/evidence validation. */
public final class Audit {
    static final String MARKER="example.boundary.PostResponseOnly#distinctiveAfterResponse()I";
    record Event(long sequence, String thread, String kind, String detail) { }
    static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    static JsonObject read(Path file)throws Exception{return JsonParser.parseString(Files.readString(file)).getAsJsonObject();}
    static Event one(List<Event> events,String kind,String detail) {
        var found=events.stream().filter(e->e.kind.equals(kind)&&e.detail.contains(detail)).toList();
        check(found.size()==1,"Expected one "+kind+" "+detail+", got "+found.size());return found.get(0);
    }
    public static void main(String[] args)throws Exception {
        Path raw=Path.of(args[0]);var summary=new TreeMap<String,Object>();String revision=null;
        for(String label:List.of("jacoco-sequential","jacoco-parallel","asm-sequential","asm-parallel","jacoco-parallel-repeat")) {
            String mode=label.replace("-repeat","");
            Path dir=raw.resolve(label);boolean asm=mode.startsWith("asm"),parallel=mode.endsWith("parallel");
            var events=new ArrayList<Event>();
            for(String line:Files.readAllLines(dir.resolve("events.tsv"))) {
                String[] p=line.split("\t",5);events.add(new Event(Long.parseLong(p[0]),p[2],p[3],p.length>4?p[4]:""));
            }
            var row=new TreeMap<String,Object>();
            String rev=Files.readString(dir.resolve("fixture-revision.txt")).trim();
            if(revision==null)revision=rev;check(revision.equals(rev),"Fixture revisions differ");
            check(Files.readString(dir.resolve("source-diff.txt")).isEmpty(),"Fixture changed");
            var response=one(events,"SIGNAL","RESPONSE_READ");var release=one(events,"SIGNAL","RELEASE_LATE");
            var done=one(events,"DISTINCTIVE_RETURN","41327");
            check(response.sequence<release.sequence&&release.sequence<done.sequence,"After-response ordering not proved");
            check(done.thread.startsWith("http-nio-"),"Action not on server worker");
            String start=asm?"ASM_beginTest_EXIT":"JACOCO_DATA_startSession_EXIT";
            String finish=asm?"ASM_endTest_EXIT":"JACOCO_LISTENER_executionFinished_EXIT";
            var aStart=one(events,start,"aRequest");var bStart=one(events,start,"bFollowing");
            var aEnd=one(events,finish,"aRequest");var bEnd=one(events,finish,"bFollowing");
            if(parallel) {
                check(aStart.sequence<done.sequence&&done.sequence<bStart.sequence&&bStart.sequence<bEnd.sequence&&bEnd.sequence<aEnd.sequence,"Parallel reset interleaving not proved");
                check(!aStart.thread.equals(bStart.thread)&&aStart.thread.startsWith("ForkJoinPool-"),"No in-JVM concurrency");
                if(!asm) {
                    var gate=one(events,"JACOCO_DATA_startSession_ENTER","bFollowing");
                    check(gate.sequence<done.sequence,"B was not held at real reset boundary");
                    check(events.stream().noneMatch(e->e.kind.equals("JACOCO_DATA_snapshotAndReset_ENTER")&&e.sequence>done.sequence&&e.sequence<bStart.sequence),"Snapshot saved marker before destructive reset");
                }
            } else check(response.sequence<aEnd.sequence&&aEnd.sequence<bStart.sequence&&bStart.sequence<done.sequence&&done.sequence<bEnd.sequence,"Sequential cross-boundary schedule not proved");
            var fragment=read(dir.resolve("stp/coverage-fragment-v3.json"));
            boolean complete=fragment.getAsJsonObject("collection").get("completed").getAsBoolean();
            check(complete != (asm&&parallel),"Unexpected completeness: "+mode);
            check(Files.readString(dir.resolve("exit-codes.txt")).equals(asm&&parallel?"mapping=1\naggregation=1\n":"mapping=0\naggregation=0\n"),"Unexpected Maven outcome");
            var expected=Set.of("example.boundary.BoundaryTests#aRequest","example.boundary.BoundaryTests#bFollowing");
            var actual=new TreeSet<String>();var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            try(var paths=Files.list(dir.resolve("surefire-reports"))) {for(Path p:paths.filter(f->f.getFileName().toString().startsWith("TEST-")&&f.toString().endsWith(".xml")).toList()) {
                var tests=factory.newDocumentBuilder().parse(p.toFile()).getElementsByTagName("testcase");
                for(int i=0;i<tests.getLength();i++) {
                    Element t=(Element)tests.item(i);check(t.getElementsByTagName("failure").getLength()+t.getElementsByTagName("error").getLength()+t.getElementsByTagName("skipped").getLength()==0,"Test did not pass");
                    check(actual.add(t.getAttribute("classname")+"#"+t.getAttribute("name")),"Duplicate executed identity");
                }
            }}
            check(actual.equals(expected),"Wrong test identities");
            row.put("tests",actual);row.put("complete",complete);row.put("fixtureRevision",rev);
            row.put("responseRead",response);row.put("release",release);row.put("distinctiveReturn",done);
            row.put("requestStart",aStart);row.put("requestEnd",aEnd);row.put("followingStart",bStart);row.put("followingEnd",bEnd);
            row.put("fragment",fragment);row.put("productionValidation",read(dir.resolve("validation.json")));
            if(complete)row.put("productionAccounting",read(dir.resolve("accounting.json")));
            if(asm) {
                var diagnostic=read(dir.resolve("stp/asm-agent-diagnostic.json"));
                var errors=diagnostic.getAsJsonArray("agentErrors");
                if(parallel)check(errors.size()==1&&errors.get(0).getAsString().equals("setup:ASYNC_SETUP_UNSUPPORTED:ERROR:unknown:ForkJoinTask: execution without observed submission: org.junit.platform.engine.support.hierarchical.ForkJoinPoolHierarchicalTestExecutorService$ExclusiveTask"),"Unexpected parallel ASM error");
                else check(errors.isEmpty(),"Unexpected sequential ASM error/setup diagnostic");
                var counts=new TreeMap<String,Long>();
                for(String reason:List.of("NO_ACTIVE_TEST","LATE_EVENT","UNKNOWN_CONTEXT","TEST_ALREADY_FINISHED","STARTUP","SHUTDOWN","TRANSFORMATION_ERROR"))counts.put(reason,0L);
                var runtime=diagnostic.getAsJsonObject("runtimeEvents");
                for(JsonElement e:runtime.getAsJsonArray("unattributedEvents")){var o=e.getAsJsonObject();counts.merge(o.get("reason").getAsString(),o.get("count").getAsLong(),Long::sum);}
                for(JsonElement t:runtime.getAsJsonArray("tests"))for(JsonElement e:t.getAsJsonObject().getAsJsonArray("unattributedEvents")){var o=e.getAsJsonObject();counts.merge(o.get("reason").getAsString(),o.get("count").getAsLong(),Long::sum);}
                check(counts.get("NO_ACTIVE_TEST")== (parallel?2L:0L) && counts.entrySet().stream().filter(e->!e.getKey().equals("NO_ACTIVE_TEST")).allMatch(e->e.getValue()==0),"Unexpected ASM unattributed events");
                var hit=one(events,"ASM_HIT","binaryClassName=example.boundary.PostResponseOnly, methodName=distinctiveAfterResponse");
                check(hit.detail.contains("active=Optional.empty"),"Unexpected leaf owner");
                if(parallel)check(hit.detail.contains("captured=null open=false"),"Unexpected parallel captured owner");
                else check(hit.detail.contains("open=true")&&hit.detail.contains("binaryName=example.boundary.BoundaryTests"),"Wrong observed setup owner");
                var scopes=fragment.getAsJsonArray("setupScopes");
                check(scopes.size()==1&&scopes.get(0).getAsJsonObject().getAsJsonArray("coveredClasses").toString().contains("example.boundary.PostResponseOnly")!=parallel,"Unexpected marker setup scope");
                for(var test:fragment.getAsJsonObject("tests").entrySet())check(test.getValue().getAsJsonObject().getAsJsonArray("methods").isEmpty(),"Unexpected ASM leaf edge");
                row.put("observedMarkerOwnership",hit);row.put("unattributedCountsGlobalAndLeaf",counts);row.put("agentErrors",diagnostic.get("agentErrors"));
            } else {
                var oracle=read(dir.resolve("session-oracle.json"));var owners=new ArrayList<String>();
                for(var session:oracle.entrySet())for(JsonElement method:session.getValue().getAsJsonObject().getAsJsonArray("coveredMethods"))if(method.getAsString().equals(MARKER))owners.add(session.getKey());
                if(parallel)check(owners.isEmpty(),"Marker was not lost");
                else check(owners.size()==1&&owners.get(0).contains("b~Following"),"Marker not attributed to following test");
                row.put("markerSessionOwners",owners);row.put("rawSessionOracle",oracle);
            }
            summary.put(label,row);
        }
        Files.writeString(raw.resolve("summary.json"),new GsonBuilder().setPrettyPrinting().create().toJson(summary)+"\n");
        System.out.println("PASS measurement: sequential JaCoCo bleed and parallel loss accepted complete; ASM sequential setup ownership retained; ASM parallel rejected with ASYNC_SETUP_UNSUPPORTED and NO_ACTIVE_TEST=2");
    }
}
