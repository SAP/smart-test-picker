package a;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AValueIT {
    @Test void integrationValue() { assertEquals(1, new AValue().value()); }
}
