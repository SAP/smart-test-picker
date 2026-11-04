import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** ASM-14 differential reused for class-only permutations; no production behavior is changed. */
public final class Differential {
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    static final List<String> ORDERS=List.of("alpha","random1","random2");
    record Run(Map<String,Set<String>> tests,Map<String,List<String>> order,JsonArray scopes,Map<String,Integer> positions) { }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static JsonObject read(Path p)throws Exception{return JsonParser.parseString(Files.readString(p)).getAsJsonObject();}
    static TreeSet<String> strings(JsonArray a){var s=new TreeSet<String>();a.forEach(e->s.add(e.getAsString()));return s;}
    static String owner(String test){return test.substring(0,test.indexOf('#'));}
    static Set<String> edges(Run run){var e=new TreeSet<String>();run.tests.forEach((t,ms)->ms.forEach(m->e.add(t+" -> "+m)));return e;}
    static Run run(Path dir)throws Exception {
        check(Files.readString(dir.resolve("exit-code.txt")).trim().equals("0"),"Failed run "+dir);
        check(Files.readString(dir.resolve("upstream-source-diff.txt")).isEmpty(),"Subject modified");
        check(Files.readString(dir.resolve("revision.txt")).trim().equals("88e37c15cf6fc8490b01bc3e8e2c800cec1ac272"),"Wrong revision");
        var fragment=read(dir.resolve("stp/coverage-fragment-v3.json"));
        check(fragment.getAsJsonObject("collection").get("completed").getAsBoolean(),"Incomplete fragment");
        var tests=new TreeMap<String,Set<String>>();
        fragment.getAsJsonObject("tests").entrySet().forEach(e->{
            var value=e.getValue().getAsJsonObject();check(value.get("outcome").getAsString().equals("PASS"),"Not passing");
            tests.put(e.getKey(),strings(value.getAsJsonArray("methods")));
        });
        check(tests.size()==69&&fragment.getAsJsonArray("unmapped").isEmpty(),"Wrong test scope");
        if(Files.exists(dir.resolve("session-oracle.json"))) {
            var oracle=new TreeMap<String,Set<String>>();
            for(var entry:read(dir.resolve("session-oracle.json")).entrySet()) {
                var row=entry.getValue().getAsJsonObject();if(!row.has("identity"))continue;
                Properties p=new Properties();p.load(new java.io.StringReader(row.get("identity").getAsString()));
                String id="maven:.::"+p.getProperty("className")+"#"+p.getProperty("methodName");
                check(p.getProperty("methodParameterTypes","").isEmpty(),"Unexpected parameter identity");
                check(oracle.put(id,strings(row.getAsJsonArray("coveredMethods")))==null,"Duplicate raw identity");
            }
            check(oracle.equals(tests),"Raw session oracle differs from production fragment: "+dir);
        }
        var order=new TreeMap<String,List<String>>();var positions=new TreeMap<String,Integer>();
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        try(var files=Files.list(dir.resolve("surefire-reports"))) {
            for(Path file:files.filter(p->p.getFileName().toString().startsWith("TEST-")&&p.toString().endsWith(".xml")).sorted().toList()) {
                var doc=factory.newDocumentBuilder().parse(file.toFile());var properties=doc.getElementsByTagName("property");
                var config=new TreeMap<String,String>();for(int i=0;i<properties.getLength();i++){var p=(Element)properties.item(i);config.put(p.getAttribute("name"),p.getAttribute("value"));}
                check("false".equals(config.get("junit.jupiter.execution.parallel.enabled")),"Parallel execution not disabled");
                String label=dir.getFileName().toString();
                check("org.junit.jupiter.api.MethodOrderer$MethodName".equals(config.get("junit.jupiter.testmethod.order.default")),"Method order changed");
                check(("org.junit.jupiter.api.ClassOrderer$"+(label.endsWith("-alpha")?"ClassName":"Random")).equals(config.get("junit.jupiter.testclass.order.default")),"Wrong effective class order");
                check((label.endsWith("-random1")?"8675309":"20261104").equals(config.get("junit.jupiter.execution.order.random.seed")),"Wrong effective seed");
                var cases=doc.getElementsByTagName("testcase");
                for(int i=0;i<cases.getLength();i++) {
                    var t=(Element)cases.item(i);check(t.getElementsByTagName("failure").getLength()+t.getElementsByTagName("error").getLength()+t.getElementsByTagName("skipped").getLength()==0,"Unsuccessful case");
                    String id="maven:.::"+t.getAttribute("classname")+"#"+t.getAttribute("name");
                    var list=order.computeIfAbsent(owner(id),k->new ArrayList<>());list.add(id);
                    check(positions.put(id,list.size())==null,"Duplicate XML test");
                }
            }
        }
        for(var list:order.values())check(list.equals(list.stream().sorted().toList()),"Actual MethodName order is not lexical");
        check(positions.keySet().equals(tests.keySet()),"XML versus fragment scope");
        var sequence=Power.sequence(dir);
        positions.clear();for(int i=0;i<sequence.size();i++)positions.put(sequence.get(i),i+1);
        return new Run(tests,order,fragment.getAsJsonArray("setupScopes"),positions);
    }
    public static void main(String[] args)throws Exception {
        // Offline positive/negative controls, not edits to any measured subject or evidence.
        var before=new Run(Map.of("testA",Set.of("shared","onlyA"),"testB",Set.of("shared","onlyB")),Map.of(),new JsonArray(),Map.of());
        var swapped=new Run(Map.of("testA",Set.of("shared","onlyB"),"testB",Set.of("shared","onlyA")),Map.of(),new JsonArray(),Map.of());
        check(Power.symmetric(edges(before),edges(swapped)).size()==4,"Equal-count owner swaps must be detected");
        check(Power.symmetric(Set.of("shared"),Set.of("shared")).isEmpty(),"Identical pair sets must have zero swap power");
        System.out.println("PASS: offline differential controls detect an equal-count owner swap and identify identical-set blindness");
        Path raw=Path.of(args[0]);var all=new TreeMap<String,Run>();
        for(String collector:List.of("asm","jacoco"))for(String order:ORDERS)all.put(collector+"-"+order,run(raw.resolve(collector+"-"+order)));
        Map<String,String> referenceHashes=null;
        for(String label:all.keySet()) {
            Path classes=raw.resolve(label).resolve("classes");var hashes=new TreeMap<String,String>();
            try(var files=Files.walk(classes)){for(Path p:files.filter(f->f.toString().endsWith(".class")).toList())hashes.put(classes.relativize(p).toString(),HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));}
            if(referenceHashes==null)referenceHashes=hashes;else check(referenceHashes.equals(hashes),"Production class bytes differ: "+label);
        }
        var sequences=new TreeMap<String,List<String>>();
        for(String label:all.keySet()) {
            sequences.put(label,Power.sequence(raw.resolve(label)));
            check(all.get(label).order.equals(all.get("asm-alpha").order),"Within-class methods changed: "+label);
        }
        for(String order:ORDERS)check(sequences.get("asm-"+order).equals(sequences.get("jacoco-"+order)),"Collectors executed different class orders");
        check(ORDERS.stream().map(o->sequences.get("asm-"+o)).distinct().count()==3,"Class orders not distinct");
        var result=new TreeMap<String,Object>();
        for(String collector:List.of("asm","jacoco")) {
            var union=new TreeSet<String>();Set<String> stable=null;var methods=new TreeSet<String>();var counts=new TreeMap<String,Integer>();
            var orders=new TreeMap<String,Object>();var scopes=new TreeMap<String,Object>();var setupMethods=new TreeMap<String,Object>();
            for(String order:ORDERS){var r=all.get(collector+"-"+order);var e=edges(r);union.addAll(e);if(stable==null)stable=new TreeSet<>(e);else stable.retainAll(e);r.tests.values().forEach(methods::addAll);counts.put(order,e.size());orders.put(order,r.order);scopes.put(order,r.scopes);}
            if(collector.equals("jacoco"))for(String order:ORDERS) {
                var setup=new TreeMap<String,Set<String>>();
                for(var entry:read(raw.resolve(collector+"-"+order).resolve("session-oracle.json")).entrySet()) {
                    var value=entry.getValue().getAsJsonObject();if(!value.has("setup"))continue;
                    Properties p=new Properties();p.load(new java.io.StringReader(value.get("setup").getAsString()));
                    check(setup.put(p.getProperty("container"),strings(value.getAsJsonArray("coveredMethods")))==null,"Duplicate setup session");
                }
                setupMethods.put(order,setup);
            }
            var variable=new TreeSet<>(union);variable.removeAll(stable);
            var movers=new TreeMap<String,Object>();
            for(String method:methods) {
                var owners=new TreeMap<String,Set<String>>();var ranks=new TreeMap<String,Map<String,List<Integer>>>();var details=new TreeMap<String,Object>();
                for(String order:ORDERS) {
                    var r=all.get(collector+"-"+order);var ids=new TreeSet<String>();var byClass=new TreeMap<String,List<Integer>>();var rows=new ArrayList<Object>();
                    r.tests.forEach((id,ms)->{if(ms.contains(method)){ids.add(id);byClass.computeIfAbsent(owner(id),k->new ArrayList<>()).add(r.positions.get(id));rows.add(Map.of("test",id,"globalPosition",r.positions.get(id),"classSize",r.order.get(owner(id)).size()));}});
                    byClass.values().forEach(Collections::sort);owners.put(order,ids);ranks.put(order,byClass);details.put(order,rows);
                }
                if(new HashSet<>(owners.values()).size()>1) {
                    var changingTests=new TreeSet<String>();owners.values().forEach(changingTests::addAll);
                    changingTests.removeIf(id->ORDERS.stream().allMatch(o->owners.get(o).contains(id)));
                    movers.put(method,Map.of("owners",owners,"positions",details,"variableTests",changingTests,
                        "rankSetsInvariantAcrossOrders",new HashSet<>(ranks.values()).size()==1));
                }
            }
            var collectorResult=new TreeMap<String,Object>();
            collectorResult.putAll(Map.of("edgeCounts",counts,"stableEdgeCount",stable.size(),"unionEdgeCount",union.size(),
                "variableEdgeCount",variable.size(),"moverMethodCount",movers.size(),"movers",movers,"stableEdges",stable,"variableEdges",variable,"actualOrders",orders,"setupScopes",scopes));
            var actualSequences=new TreeMap<String,Object>();for(String order:ORDERS)actualSequences.put(order,sequences.get(collector+"-"+order));
            collectorResult.put("actualSequences",actualSequences);
            collectorResult.put("setupScopesInvariant",new HashSet<>(scopes.values()).size()==1);
            if(!setupMethods.isEmpty()){collectorResult.put("rawSetupMethods",setupMethods);collectorResult.put("rawSetupMethodsInvariant",new HashSet<>(setupMethods.values()).size()==1);}
            result.put(collector,collectorResult);
            System.out.println(collector+": perRun="+counts+" stable="+stable.size()+" variableEdges="+variable.size()+" moverMethods="+movers.size());
        }
        Files.writeString(raw.resolve("differential.json"),JSON.toJson(result)+"\n");
        System.out.println("PASS: exact identities/outcomes, distinct actual class orders, fixed within-class method order, collector-paired orders, raw JaCoCo oracle equality and sequential configuration");
    }
}
