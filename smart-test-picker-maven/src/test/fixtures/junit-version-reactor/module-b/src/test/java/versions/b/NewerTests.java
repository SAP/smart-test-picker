package versions.b;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
class NewerTests {
  @ParameterizedTest @ValueSource(strings = {"one", "two"}) void parameterized(String value) {}
}
