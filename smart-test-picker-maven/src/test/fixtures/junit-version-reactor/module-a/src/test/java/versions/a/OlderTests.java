package versions.a;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
class OlderTests {
  @Test void older() {}
  @Nested class NestedTests { @Test void nested() {} }
}
