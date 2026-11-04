package fixture;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrdinaryTest {
    @Test void first() { assertOrdinaryRun(); }
    @Test void second() { assertOrdinaryRun(); }

    private void assertOrdinaryRun() {
        assertNull(System.getProperty("smartTestPicker.collectionEnabled"));
        assertNull(System.getProperty("smartTestPicker.coverageCollector"));
        assertNull(System.getProperty("stp.exec.dir"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.jacoco.agent.rt.RT"));
        assertNotNull(com.sap.oss.smarttestpicker.JacocoPerTestListener.class);
    }
}
