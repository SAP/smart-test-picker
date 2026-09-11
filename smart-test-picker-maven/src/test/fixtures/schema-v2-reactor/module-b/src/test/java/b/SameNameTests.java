package b; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.assertEquals; class SameNameTests { @Test void beta() { assertEquals(2, new ServiceB().value()); } }
