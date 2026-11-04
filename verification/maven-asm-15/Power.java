import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Offline boundary-power measurement; never modifies input evidence or a subject. */
public final class Power {
    static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static JsonObject read(Path p) throws Exception { return JsonParser.parseString(Files.readString(p)).getAsJsonObject(); }
    static String owner(String id) { return id.substring(0,id.indexOf('#')); }
    static List<String> topClasses(Path run) throws Exception {
        var result = new ArrayList<String>();
        for (String line : Files.readAllLines(run.resolve("mapping.log"))) {
            String prefix = "[INFO] Running ";
            if (line.startsWith(prefix) && !line.substring(prefix.length()).contains("$")) result.add(line.substring(prefix.length()).trim());
        }
        check(result.size()==15 && new HashSet<>(result).size()==15, "Expected 15 unique top-level classes: "+run);
        return result;
    }
    static List<String> sequence(Path run) throws Exception {
        var sequence = new ArrayList<String>();
        var f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        for (String cls : topClasses(run)) {
            var doc = f.newDocumentBuilder().parse(run.resolve("surefire-reports/TEST-"+cls+".xml").toFile());
            var tests = doc.getElementsByTagName("testcase");
            for (int i=0;i<tests.getLength();i++) {
                var test=(Element)tests.item(i);
                sequence.add("maven:.::"+test.getAttribute("classname")+"#"+test.getAttribute("name"));
            }
        }
        check(sequence.size()==69 && new HashSet<>(sequence).size()==69,"Expected 69 unique leaves");
        return sequence;
    }
    static Map<String,Set<String>> methods(Path run) throws Exception {
        var result=new TreeMap<String,Set<String>>();
        for(var e:read(run.resolve("stp/coverage-fragment-v3.json")).getAsJsonObject("tests").entrySet()) {
            var set=new TreeSet<String>();e.getValue().getAsJsonObject().getAsJsonArray("methods").forEach(m->set.add(m.getAsString()));result.put(e.getKey(),set);
        }
        if(Files.exists(run.resolve("session-oracle.json"))) {
            var oracle=new TreeMap<String,Set<String>>();
            for(var e:read(run.resolve("session-oracle.json")).entrySet()) {
                var row=e.getValue().getAsJsonObject();if(!row.has("identity"))continue;
                var p=new Properties();p.load(new java.io.StringReader(row.get("identity").getAsString()));
                var set=new TreeSet<String>();row.getAsJsonArray("coveredMethods").forEach(m->set.add(m.getAsString()));
                check(oracle.put("maven:.::"+p.getProperty("className")+"#"+p.getProperty("methodName"),set)==null,"Duplicate oracle identity");
            }
            check(oracle.equals(result),"Raw-session oracle mismatch");
        }
        return result;
    }
    static Set<String> symmetric(Set<String> a,Set<String>b) {var s=new TreeSet<>(a);s.addAll(b);var common=new HashSet<>(a);common.retainAll(b);s.removeAll(common);return s;}
    static Set<String> boundaries(List<String> sequence, boolean crossOnly) {
        var set=new TreeSet<String>();
        for(int i=0;i+1<sequence.size();i++)if(!crossOnly||!owner(sequence.get(i)).equals(owner(sequence.get(i+1))))set.add(sequence.get(i)+" -> "+sequence.get(i+1));
        return set;
    }
    public static void main(String[] args) throws Exception {
        if(args[0].equals("--combine")) {
            var old=read(Path.of(args[1])).getAsJsonObject("jacoco");var current=read(Path.of(args[2])).getAsJsonObject("jacoco");
            var outgoing=new TreeSet<String>();var incident=new TreeSet<String>();
            for(var data:List.of(old,current)) {
                data.getAsJsonArray("outgoingDetectableSuspectEdges").forEach(e->outgoing.add(e.getAsString()));
                data.getAsJsonArray("incidentDetectableSuspectEdges").forEach(e->incident.add(e.getAsString()));
            }
            var remaining=new TreeSet<String>();
            for(var element:read(Path.of(args[3])).getAsJsonArray("divergentEdges")) {
                var e=element.getAsJsonObject();if(e.get("side").getAsString().equals("JACOCO_ONLY"))remaining.add(e.get("test").getAsString()+" -> "+e.get("method").getAsString());
            }
            check(remaining.size()==74,"Expected original suspect ledger");remaining.removeAll(outgoing);
            Files.writeString(Path.of(args[4]),JSON.toJson(Map.of("outgoingDetectableSuspectEdges",outgoing,"incidentDetectableSuspectEdges",incident,
                "outgoingCount",outgoing.size(),"incidentCount",incident.size(),"remainingOutgoingBlindEdges",remaining))+"\n");
            System.out.println("Combined outgoing boundary power="+outgoing.size()+"/74; remaining="+remaining.size());return;
        }
        Path raw=Path.of(args[0]), ledger=Path.of(args[1]), output=Path.of(args[2]);
        List<String> orders=List.of(args[3].split(","));boolean crossOnly=Boolean.parseBoolean(args[4]);
        var suspects=new TreeMap<String,Set<String>>();
        for(var e:read(ledger).getAsJsonArray("divergentEdges")) {
            var row=e.getAsJsonObject();if(!row.get("side").getAsString().equals("JACOCO_ONLY"))continue;
            suspects.computeIfAbsent(row.get("test").getAsString(),k->new TreeSet<>()).add(row.get("method").getAsString());
        }
        check(suspects.values().stream().mapToInt(Set::size).sum()==74,"Expected original 74 suspect edges");
        var outputData=new TreeMap<String,Object>();
        for(String collector:List.of("asm","jacoco")) {
            var sequences=new TreeMap<String,List<String>>();var tables=new TreeMap<String,Map<String,Set<String>>>();
            var byOrder=new TreeMap<String,Set<String>>();var union=new TreeSet<String>();Set<String> intersection=null;
            for(String order:orders) {
                Path run=raw.resolve(collector+"-"+order);var seq=sequence(run);sequences.put(order,seq);tables.put(order,methods(run));
                if(Files.exists(raw.resolve("differential.json"))) {
                    var expected=read(raw.resolve("differential.json")).getAsJsonObject(collector).getAsJsonObject("actualOrders").getAsJsonObject(order);
                    var projected=new TreeMap<String,List<String>>();for(String id:seq)projected.computeIfAbsent(owner(id),k->new ArrayList<>()).add(id);
                    for(var e:projected.entrySet())check(JSON.toJsonTree(e.getValue()).equals(expected.get(e.getKey())),"Sequence disagrees with retained declaring-class order");
                }
                var b=boundaries(seq,crossOnly);byOrder.put(order,b);union.addAll(b);if(intersection==null)intersection=new TreeSet<>(b);else intersection.retainAll(b);
            }
            union.removeAll(intersection);
            var rows=new ArrayList<Object>();var incident=new TreeSet<String>();var outgoing=new TreeSet<String>();
            var incidentPairOnly=new TreeSet<String>();var outgoingPairOnly=new TreeSet<String>();int detectable=0;
            var reversedIncident=new TreeSet<String>();var reversedOutgoing=new TreeSet<String>();int reversedPairs=0,reversedDetectable=0;
            var reversedIncidentPair=new TreeSet<String>();var reversedOutgoingPair=new TreeSet<String>();
            for(String pair:union) {
                String[] ids=pair.split(" -> ",2);var perOrder=new TreeMap<String,Object>();boolean any=false;
                boolean reversal=orders.stream().anyMatch(o->sequences.get(o).indexOf(ids[0])>sequences.get(o).indexOf(ids[1]));
                for(String order:orders) {
                    var table=tables.get(order);var sym=symmetric(table.get(ids[0]),table.get(ids[1]));
                    boolean present=byOrder.get(order).contains(pair);any|=present&&!sym.isEmpty();
                    perOrder.put(order,Map.of("adjacent",present,"firstPosition",sequences.get(order).indexOf(ids[0])+1,
                        "secondPosition",sequences.get(order).indexOf(ids[1])+1,"symmetricDifferenceSize",sym.size(),"symmetricDifference",sym,"detectable",!sym.isEmpty()));
                    if(present&&!sym.isEmpty())for(int i=0;i<2;i++)for(String method:suspects.getOrDefault(ids[i],Set.of())) {
                        if(!table.get(ids[i]).contains(method))continue;
                        String edge=ids[i]+" -> "+method;incidentPairOnly.add(edge);if(i==0)outgoingPairOnly.add(edge);
                        if(reversal){reversedIncidentPair.add(edge);if(i==0)reversedOutgoingPair.add(edge);}
                        if(sym.contains(method)){incident.add(edge);if(i==0)outgoing.add(edge);
                            if(reversal){reversedIncident.add(edge);if(i==0)reversedOutgoing.add(edge);}}
                    }
                }
                if(any)detectable++;
                if(reversal){reversedPairs++;if(any)reversedDetectable++;}
                rows.add(Map.of("first",ids[0],"second",ids[1],"relativeOrderReversed",reversal,"orders",perOrder));
            }
            var details=new TreeMap<String,Object>();
            for(var e:suspects.entrySet()) {
                String id=e.getKey();var successors=new TreeMap<String,String>();var predecessors=new TreeMap<String,String>();var positions=new TreeMap<String,Integer>();
                for(String order:orders){var seq=sequences.get(order);int i=seq.indexOf(id);positions.put(order,i+1);successors.put(order,i+1<seq.size()?seq.get(i+1):"END");predecessors.put(order,i>0?seq.get(i-1):"START");}
                long classCount=sequences.get(orders.get(0)).stream().filter(t->owner(t).equals(owner(id))).count();
                var methodSets=new TreeMap<String,Set<String>>();for(String o:orders)methodSets.put(o,tables.get(o).get(id));
                var inc=new TreeSet<String>();var out=new TreeSet<String>();for(String m:e.getValue()){if(incident.contains(id+" -> "+m))inc.add(m);if(outgoing.contains(id+" -> "+m))out.add(m);}
                details.put(id,Map.of("declaringClassMethodCount",classCount,"suspectEdgeCount",e.getValue().size(),"positions",positions,"predecessors",predecessors,"successors",successors,"coveredMethods",methodSets,"incidentDetectableSuspectMethods",inc,"outgoingDetectableSuspectMethods",out));
            }
            var result=new TreeMap<String,Object>();result.putAll(Map.of("changedDirectedPairCount",rows.size(),"detectableChangedPairCount",detectable,"pairs",rows,"sequences",sequences,"suspectTests",details,
                "incidentPairLevelSuspectCount",incidentPairOnly.size(),"outgoingPairLevelSuspectCount",outgoingPairOnly.size(),"incidentEdgeLevelSuspectCount",incident.size(),"outgoingEdgeLevelSuspectCount",outgoing.size()));
            result.put("incidentDetectableSuspectEdges",incident);result.put("outgoingDetectableSuspectEdges",outgoing);outputData.put(collector,result);
            result.put("relativeReversal",Map.of("directedPairCount",reversedPairs,"detectablePairCount",reversedDetectable,
                "incidentSuspectEdges",reversedIncident,"outgoingSuspectEdges",reversedOutgoing,
                "incidentPairLevelSuspectCount",reversedIncidentPair.size(),"outgoingPairLevelSuspectCount",reversedOutgoingPair.size()));
            System.out.println(collector+": changed boundaries="+rows.size()+", detectable="+detectable+", suspect incident edges="+incident.size()+"/74, successor edges="+outgoing.size()+"/74");
        }
        Files.createDirectories(output.getParent());Files.writeString(output,JSON.toJson(outputData)+"\n");
    }
}
