package a; import org.junit.jupiter.api.Test; class ATests { @Test void a1() { new AValue().value(); } @Test void a2() { throw new AssertionError("A2 outside assignment executed"); } }
