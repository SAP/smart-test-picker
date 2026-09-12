package b; import org.junit.jupiter.api.Test; class BTests { @Test void b1() { throw new AssertionError("B1 outside assignment executed"); } @Test void b2() { new BValue().value(); } }
