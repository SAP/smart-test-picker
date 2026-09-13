package a;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ATests {
	@Test void a1() {
		if (!"true".equals(System.getProperty("some.project.property")) && Boolean.getBoolean("literal.fixture.required"))
			throw new AssertionError("project argLine property missing");
		new AValue().value();
	}
	@Test void a2() { throw new AssertionError("A2 outside assignment executed"); }
	@ParameterizedTest @ValueSource(strings = {"one", "two"}) void parameterized(String value) { new AValue().value(); }
	@Disabled @ParameterizedTest @ValueSource(strings = "unused") void disabledParameterized(String value) { }
}
