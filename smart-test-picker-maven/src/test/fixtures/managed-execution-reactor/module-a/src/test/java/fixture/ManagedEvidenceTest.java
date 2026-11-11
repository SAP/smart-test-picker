package fixture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.Extension;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedEvidenceTest {
	@Test void selected() {
		assertTrue(ServiceLoader.load(Extension.class).stream()
				.anyMatch(provider -> provider.type().getName().equals("com.sap.oss.smarttestpicker.TestLifecycleExtension")));
	}
    @Test void notSelected() { }
}
