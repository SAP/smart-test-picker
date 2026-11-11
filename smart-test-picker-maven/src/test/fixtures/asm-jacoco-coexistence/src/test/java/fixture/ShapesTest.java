package fixture;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;

class ShapesTest {
    @BeforeAll static void recordFork() throws Exception {
        assertEquals("preserved", System.getProperty("project.literal"));
        assertEquals(Boolean.getBoolean("expect.stp"), Thread.currentThread().getContextClassLoader()
                .getResource("com/sap/oss/smarttestpicker/JacocoPerTestListener.class") != null);
        java.nio.file.Files.write(java.nio.file.Path.of("target/fork-arguments.txt"),
                java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
    }
    @Test void enumShape() { assertEquals("VALUE", EmptyEnum.VALUE.name()); }
    @Test void recordShape() {
        var record = new ValueRecord("record", 7);
        assertEquals("record", record.text()); assertEquals(7, record.number());
    }
    @Test void bridgeShape() {
        GenericValue<String> value = new BridgeValue();
        assertEquals("bridge", value.get());
    }
    @Test void implicitExceptionShape() {
        assertThrows(NullPointerException.class, () -> new ImplicitException().length(null));
    }
    @AfterAll static void observerPositiveControl() throws Exception {
        if (!Boolean.getBoolean("probe.control")) return;
        Object agent = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
        agent.getClass().getMethod("reset").invoke(agent);
        agent.getClass().getMethod("getExecutionData", boolean.class).invoke(agent, true);
        agent.getClass().getMethod("dump", boolean.class).invoke(agent, false);
    }
}
