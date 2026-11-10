import java.nio.file.*;
import java.util.Set;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;
import com.sap.oss.smarttestpicker.coverage.model.MethodIdentity;

/** Validate all benchmark agent outputs using the unchanged production codec. */
class VerifyFragments {
    public static void main(String[] args) throws Exception {
        int count = 0;
        try (var files = Files.walk(Path.of(args[0]))) {
            for (var path : files.filter(p -> p.getFileName().toString().equals("fragment.json")).sorted().toList()) {
                var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(path));
                if (!fragment.collectionCompleted() || fragment.tests().size() != 1 || !fragment.unmapped().isEmpty())
                    throw new AssertionError("Incomplete benchmark: " + path);
                var entry = fragment.tests().entrySet().iterator().next();
                if (!entry.getKey().toString().equals("maven:.::perf.BenchTest#measure")
                        || !entry.getValue().coveredClasses().equals(Set.of("perf.subject.HotMethod"))
                        || !entry.getValue().coveredMethods().equals(Set.of(new MethodIdentity("perf.subject.HotMethod", "touch", "(J)J"))))
                    throw new AssertionError("Wrong coverage: " + path + " " + entry);
                System.out.println("PASS " + path.getParent().getFileName() + " " + entry);
                count++;
            }
        }
        if (count != 24) throw new AssertionError("Expected 24 agent samples, got " + count);
        System.out.println("PASS: all 24 agent fragments through the production codec");
    }
}
