import com.google.gson.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

/** Extends ASM-14's retained Differential utilities with explicit target normalization and scope checks. */
public class Compare {
    static final String MAVEN="maven:.::", GRADLE="gradle::test::";
    static final List<String> REASONS=List.of("NO_ACTIVE_TEST","TEST_ALREADY_FINISHED","UNKNOWN_CONTEXT",
            "STARTUP","SHUTDOWN","LATE_EVENT","TRANSFORMATION_ERROR");
    static JsonObject read(Path p)throws Exception{return Differential.read(p);}
    static void check(boolean v,String m){Differential.check(v,m);}
    static void write(Path p,Object value)throws Exception{Files.writeString(p,Differential.JSON.toJson(value)+"\n");}
    static Map<String,JsonObject> tests(JsonObject fragment,String prefix){
        var result=new TreeMap<String,JsonObject>();
        fragment.getAsJsonObject("tests").entrySet().forEach(e->{
            check(e.getKey().startsWith(prefix),"Unexpected target: "+e.getKey());
            check(result.put(e.getKey().substring(prefix.length()),e.getValue().getAsJsonObject())==null,"Duplicate logical identity");
        });return result;
    }
    static Set<String> edges(Map<String,JsonObject> tests,String field){
        var rows=new TreeMap<String,Set<String>>();
        tests.forEach((t,v)->rows.put(t,Differential.strings(v.getAsJsonArray(field))));
        // Reuse ASM-14's exact test -> edge construction; no count-only comparison.
        return Differential.edges(new Differential.Run(rows,Map.of(),new JsonArray(),Map.of()));
    }
    static Set<String> minus(Set<String> a,Set<String> b){var d=new TreeSet<>(a);d.removeAll(b);return d;}
    static Object comparison(Set<String> a,Set<String> b){return Map.of("left",a,"right",b,
            "leftOnly",minus(a,b),"rightOnly",minus(b,a),"equal",a.equals(b));}
    static List<JsonObject> scopes(JsonObject fragment,String owner){
        var values=new ArrayList<JsonObject>();
        for(var entry:fragment.getAsJsonArray("setupScopes")){
            var s=entry.getAsJsonObject().deepCopy();check(owner.equals(s.get("owner").getAsString()),"Unexpected scope owner");
            s.addProperty("owner","ROOT_TEST");
            for(String key:List.of("coveredClasses","affectedContainers"))s.add(key,Differential.JSON.toJsonTree(Differential.strings(s.getAsJsonArray(key))));
            values.add(s);
        }
        values.sort(Comparator.comparing(v->v.get("id").getAsString()));return values;
    }
    static Map<String,String> executed(Path reports,Set<String> expected)throws Exception{
        var result=new TreeMap<String,String>();var logical=new TreeSet<String>();var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var files=Files.list(reports)){
            for(Path p:files.filter(f->f.getFileName().toString().startsWith("TEST-")&&f.toString().endsWith(".xml")).toList()){
                var doc=factory.newDocumentBuilder().parse(p.toFile());var cases=doc.getElementsByTagName("testcase");
                for(int i=0;i<cases.getLength();i++){
                    var t=(Element)cases.item(i);
                    check(t.getElementsByTagName("failure").getLength()+t.getElementsByTagName("error").getLength()+t.getElementsByTagName("skipped").getLength()==0,"Nonpassing test "+p);
                    String raw=t.getAttribute("classname")+"#"+t.getAttribute("name");
                    String canonical=raw;
                    // Gradle XML uses method(), Surefire uses method. Only accept this exact,
                    // reversible no-argument spelling when the production fragment confirms it.
                    if(!expected.contains(raw)&&raw.endsWith("()")&&expected.contains(raw.substring(0,raw.length()-2)))
                        canonical=raw.substring(0,raw.length()-2);
                    check(expected.contains(canonical),"Unknown XML identity "+raw);
                    check(result.put(raw,canonical)==null&&logical.add(canonical),"Duplicate XML identity");
                }
            }
        }return result;
    }
    static Map<String,Integer> reasons(JsonArray events){
        var counts=new TreeMap<String,Integer>();REASONS.forEach(r->counts.put(r,0));
        events.forEach(e->counts.merge(e.getAsJsonObject().get("reason").getAsString(),1,Integer::sum));return counts;
    }
    static Object diagnostics(JsonObject d){
        var runtime=d.getAsJsonObject("runtimeEvents");var perTest=new TreeMap<String,Object>();
        for(var t:runtime.getAsJsonArray("tests")){
            var row=t.getAsJsonObject();perTest.put(row.get("testId").getAsString(),reasons(row.getAsJsonArray("unattributedEvents")));
        }
        return Map.of("configuration",d.get("configuration"),"metrics",d.get("metrics"),
                "globalUnattributed",reasons(runtime.getAsJsonArray("unattributedEvents")),"perTestUnattributed",perTest,
                "agentErrors",d.get("agentErrors"));
    }
    static Set<String> catalog(JsonObject d){
        var result=new TreeSet<String>();d.getAsJsonArray("methodCatalog").forEach(e->result.addAll(Differential.strings(e.getAsJsonObject().getAsJsonArray("canonicalKeys"))));return result;
    }
    static Set<String> productionClasses(Path directory)throws Exception{
        var result=new TreeSet<String>();
        try(var paths=Files.walk(directory)){paths.filter(p->p.toString().endsWith(".class")).forEach(p->{
            String name=directory.relativize(p).toString().replace(java.io.File.separatorChar,'.');
            result.add(name.substring(0,name.length()-6));
        });}return result;
    }
    static Set<String> outside(Set<String> edges,Set<String> classes){
        var result=new TreeSet<String>();for(String edge:edges){
            String member=edge.substring(edge.indexOf(" -> ")+4);int at=member.indexOf('#');
            if(!classes.contains(at<0?member:member.substring(0,at)))result.add(edge);
        }return result;
    }
    public static void main(String[] args)throws Exception{
        Path raw=Path.of(args[0]), old=Path.of(args[1]);
        Path mp=raw.resolve("maven/stp/coverage-fragment-v3.json"),gp=raw.resolve("gradle/fragment.json");
        var codec=new ExecutableCoverageFragmentCodec();codec.deserialize(Files.readAllBytes(mp));codec.deserialize(Files.readAllBytes(gp));
        var m=read(mp);var g=read(gp);var baseline=read(old.resolve("module/coverage-fragment-v3.json"));
        var mt=tests(m,MAVEN);var gt=tests(g,GRADLE);
        var mxml=executed(raw.resolve("maven/surefire-reports"),mt.keySet());var gxml=executed(raw.resolve("gradle/test-results"),gt.keySet());
        var mx=new TreeSet<>(mxml.values());var gx=new TreeSet<>(gxml.values());
        // Scope equality is a prerequisite: stop before computing any coverage difference if it fails.
        check(mx.equals(gx)&&mx.size()==69,"Executed scopes differ");check(mx.equals(mt.keySet())&&gx.equals(gt.keySet()),"XML/fragment scope differs");
        write(raw.resolve("executed-identity-equality.json"),Map.of("maven",mx,"gradle",gx,"equal",true,
                "xmlNormalization",Map.of("maven",mxml,"gradle",gxml),
                "targetNormalization",Map.of("maven",MAVEN+"X#y -> X#y","gradle",GRADLE+"X#y -> X#y"),
                "scopeOwnerNormalization",Map.of("maven:.","ROOT_TEST","gradle::test","ROOT_TEST"),
                "shards",Map.of("maven",m.get("shardId"),"gradle",g.get("shardId"))));
        var ms=scopes(m,"maven:.");var gs=scopes(g,"gradle::test");
        var result=new TreeMap<String,Object>();
        result.put("methods",comparison(edges(mt,"methods"),edges(gt,"methods")));
        result.put("classes",comparison(edges(mt,"classes"),edges(gt,"classes")));
        result.put("setupScopes",Map.of("maven",ms,"gradle",gs,"equal",ms.equals(gs)));
        result.put("unmapped",Map.of("maven",m.get("unmapped"),"gradle",g.get("unmapped"),"equal",m.get("unmapped").equals(g.get("unmapped"))));
        result.put("collection",Map.of("maven",m.get("collection"),"gradle",g.get("collection")));
        result.put("outcomesAndCoverageEqual",mt.equals(gt));write(raw.resolve("differential.json"),result);
        var md=read(raw.resolve("maven/stp/asm-agent-diagnostic.json"));var gd=read(raw.resolve("gradle/agent-diagnostic.json"));
        var bd=read(old.resolve("module/asm-agent-diagnostic.json"));
        write(raw.resolve("diagnostics.json"),Map.of("maven",diagnostics(md),"gradle",diagnostics(gd),"methodCatalog",comparison(catalog(md),catalog(gd))));
        var mc=productionClasses(Path.of(args[2]));var gc=productionClasses(Path.of(args[3]));
        var intersection=new TreeSet<>(mc);intersection.retainAll(gc);
        var scopeResult=new TreeMap<String,Object>();scopeResult.put("mavenProductionClasses",mc);scopeResult.put("gradleProductionClasses",gc);
        scopeResult.put("intersection",intersection);
        for(var entry:Map.of("maven",mt,"gradle",gt).entrySet()){
            scopeResult.put(entry.getKey()+"ExcludedMethodEdges",outside(edges(entry.getValue(),"methods"),intersection));
            scopeResult.put(entry.getKey()+"ExcludedClassEdges",outside(edges(entry.getValue(),"classes"),intersection));
        }
        for(var entry:Map.of("maven",ms,"gradle",gs).entrySet()){
            var excluded=new TreeMap<String,Set<String>>();for(var scope:entry.getValue()){
                var classes=Differential.strings(scope.getAsJsonArray("coveredClasses"));classes.removeAll(intersection);
                if(!classes.isEmpty())excluded.put(scope.get("id").getAsString(),classes);
            }scopeResult.put(entry.getKey()+"ExcludedSetupClasses",excluded);
        }
        var extra=minus(catalog(gd),catalog(md));var extraHits=new JsonArray();
        for(var hit:gd.getAsJsonArray("methodHits"))if(!Collections.disjoint(extra,
                Differential.strings(hit.getAsJsonObject().getAsJsonArray("canonicalKeys"))))extraHits.add(hit);
        scopeResult.put("gradleOnlyCatalogMethods",extra);scopeResult.put("gradleOnlyCatalogMethodHits",extraHits);
        scopeResult.put("globalMethodHitRecordsEqual",md.get("methodHits").equals(gd.get("methodHits")));
        write(raw.resolve("scope-intersection.json"),scopeResult);
        var empty=new TreeSet<String>();mt.forEach((t,v)->{if(v.get("collectionStatus").getAsString().equals("COLLECTED_EMPTY"))empty.add(t);});
        write(raw.resolve("maven-regression.json"),Map.of("entireFragmentEqual",baseline.equals(m),
                "baselineMetrics",bd.get("metrics"),"freshMetrics",md.get("metrics"),"emptyIdentities",empty,
                "methodEdges",edges(mt,"methods").size(),"classEdges",edges(mt,"classes").size()));
        System.out.println("Executed identities equal: "+mx.size());
        System.out.println("Exact test coverage/outcomes equal: "+mt.equals(gt)+"; methods="+edges(mt,"methods").size()+"; classes="+edges(mt,"classes").size());
        System.out.println("Normalized setup scopes equal: "+ms.equals(gs)+"; scopes="+ms.size());
        System.out.println("Fresh Maven entire fragment equals ASM-10: "+baseline.equals(m)+"; empty identities="+empty.size());
    }
}
