import java.nio.file.*;
import com.sap.oss.smarttestpicker.coverage.serialization.ExecutableCoverageFragmentCodec;

/** Validate the real agent fragment through the production codec. */
class Verify {
    public static void main(String[] args) throws Exception {
        var fragment = new ExecutableCoverageFragmentCodec().deserialize(Files.readAllBytes(Path.of(args[0])));
        if (!fragment.collectionCompleted() || fragment.tests().size() != 1 || !fragment.unmapped().isEmpty())
            throw new AssertionError("Expected one mapped test, complete collection, and no unmapped tests");
        var entry = fragment.tests().entrySet().iterator().next();
        if (!entry.getKey().toString().equals("maven:.::probe.InMemoryTest#recordsDynamicAndControl"))
            throw new AssertionError("Unexpected identity: " + entry.getKey());
        System.out.println(entry.getKey() + " -> " + entry.getValue());
        var methods = entry.getValue().coveredMethods().toString();
        if (!methods.contains("generatedprobe.DynamicValue#value()I")
                || !methods.contains("generatedprobe.ControlValue#value()I"))
            throw new AssertionError("Missing dynamic hit or positive control: " + methods);
        System.out.println("PASS: runtime-defined and disk-loaded methods both reach the schema-v3 fragment");
    }
}
