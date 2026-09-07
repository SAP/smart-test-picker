package example;

public final class FixtureService {
    public String ordinary() { return "covered"; }
    public int parameterized(int value) { return value * 2; }
    public String nested() { return "nested"; }
    public String overloaded(String value) { return value.toUpperCase(); }
}
