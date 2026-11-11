package example;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixtureTests {
    private final FixtureService service = new FixtureService();

    @Test void ordinary() { assertEquals("covered", service.ordinary()); }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void parameterized(int value) { assertEquals(value * 2, service.parameterized(value)); }

    @Test void overloaded(TestInfo ignored) { assertEquals("A", service.overloaded("a")); }

    @Test void overloaded(TestReporter ignored) { assertEquals("B", service.overloaded("b")); }

    @Nested class Inner {
        @Test void nested() { assertEquals("nested", service.nested()); }
    }
}
