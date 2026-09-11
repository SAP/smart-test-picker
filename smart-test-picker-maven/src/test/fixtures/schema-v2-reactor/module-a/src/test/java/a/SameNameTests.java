package a; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.assertEquals; class SameNameTests { @Test void alpha() { assertEquals(1, new ServiceA().value()); } }
