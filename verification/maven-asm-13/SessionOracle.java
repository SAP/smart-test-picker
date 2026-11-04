import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import org.jacoco.core.analysis.*;
import org.jacoco.core.tools.ExecFileLoader;

/** Reads original STP session bytes against the fixture's original compiled production classes. */
public final class SessionOracle {
    public static void main(String[] args) throws Exception {
        Path sessions=Path.of(args[0]), classes=Path.of(args[1]), output=Path.of(args[2]);
        var result=new TreeMap<String,Object>();
        try(var paths=Files.list(sessions)) {
            for(Path path:paths.filter(p->p.toString().endsWith(".exec")).sorted().toList()) {
                var loader=new ExecFileLoader(); loader.load(path.toFile());
                var coverage=new CoverageBuilder();
                new Analyzer(loader.getExecutionDataStore(),coverage).analyzeAll(classes.toFile());
                var counters=new TreeMap<String,Object>();
                var covered=new TreeSet<String>();
                for(var c:coverage.getClasses()) for(var m:c.getMethods()) {
                    String id=c.getName().replace('/','.')+"#"+m.getName()+m.getDesc();
                    counters.put(id,Map.of("coveredInstructions",m.getInstructionCounter().getCoveredCount(),
                        "missedInstructions",m.getInstructionCounter().getMissedCount(),
                        "coveredMethod",m.getMethodCounter().getCoveredCount()));
                    if(m.getMethodCounter().getCoveredCount()>0)covered.add(id);
                }
                String filename=path.getFileName().toString();
                var row=new TreeMap<String,Object>();
                row.put("coveredMethods",covered); row.put("methodCounters",counters);
                row.put("embeddedSessionIds",loader.getSessionInfoStore().getInfos().stream().map(i->i.getId()).toList());
                for(String ext:List.of("identity","setup","non-executed")) {
                    Path sidecar=path.resolveSibling(filename.substring(0,filename.length()-4)+ext);
                    if(Files.exists(sidecar))row.put(ext,Files.readString(sidecar));
                }
                result.put(filename,row);
            }
        }
        Files.writeString(output,new GsonBuilder().setPrettyPrinting().create().toJson(result)+"\n");
        System.out.println("Decoded "+result.size()+" raw JaCoCo sessions into "+output);
    }
}
