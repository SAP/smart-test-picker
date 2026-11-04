import java.nio.file.*;
import java.util.*;
import com.sap.oss.smarttestpicker.selector.ExecutableHeadTestInventoryCodec;
import com.sap.oss.smarttestpicker.coverage.model.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableShardAssignmentCodec;

/** Measurement-only assignment: exact H2 scope, with real inventory and assignment codecs. */
public class Prepare {
    public static void main(String[] args) throws Exception {
        var codec = new ExecutableHeadTestInventoryCodec();
        var gradle = codec.read(Path.of(args[0]).toFile());
        var maven = codec.read(Path.of(args[1]).toFile());
        var selected = new TreeSet<ExecutableTestIdentity>();
        var excluded = new TreeSet<String>();
        for (var test : gradle.runnableTests()) {
            if (test.test().className().endsWith(".MySqlIntegrationTests")
                    || test.test().className().endsWith(".PostgresIntegrationTests")) excluded.add(test.toString());
            else selected.add(test);
        }
        var a = new TreeSet<String>(); selected.forEach(t -> a.add(t.test().toString()));
        var b = new TreeSet<String>(); maven.runnableTests().forEach(t -> b.add(t.test().toString()));
        if (!a.equals(b) || a.size()!=69) throw new AssertionError("Different H2 inventory scopes");
        Files.write(Path.of(args[2]), new ExecutableShardAssignmentCodec().serialize(
                new ExecutableShardAssignment(1, new CoverageMapRevision(gradle.revision()), new ShardId("local"), selected)));
        System.out.println("Inventory equality: 69 exact logical identities. This is not executed-scope proof yet.");
        System.out.println("Excluded database identities: " + excluded);
        for (String test : a) System.out.println(test);
    }
}
