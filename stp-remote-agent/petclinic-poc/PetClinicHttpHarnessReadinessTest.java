import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import org.junit.jupiter.api.Test;

class PetClinicHttpHarnessReadinessTest {
	private static final URI PROBE = URI.create("http://127.0.0.1:18080/actuator/health");

	@Test
	void retriesNon200ResponsesUntilItReceives200() {
		Queue<PetClinicHttpHarness.ProbeResponse> responses = new ArrayDeque<>();
		responses.add(new PetClinicHttpHarness.ProbeResponse(503, "starting"));
		responses.add(new PetClinicHttpHarness.ProbeResponse(404, "not ready"));
		responses.add(new PetClinicHttpHarness.ProbeResponse(200, "UP"));

		assertDoesNotThrow(() -> PetClinicHttpHarness.waitUntilReady(
				PROBE, Duration.ofSeconds(1), Duration.ofMillis(1), uri -> {
					assertTrue(PROBE.equals(uri), "the configured probe URL must be used every time");
					return responses.remove();
				}));
	}

	@Test
	void retriesConnectionFailureUntilItReceives200() {
		int[] calls = {0};
		assertDoesNotThrow(() -> PetClinicHttpHarness.waitUntilReady(
				PROBE, Duration.ofSeconds(1), Duration.ofMillis(1), uri -> {
					if (calls[0]++ == 0) throw new IOException("connection refused");
					return new PetClinicHttpHarness.ProbeResponse(200, "UP");
				}));
		assertTrue(calls[0] == 2);
	}

	@Test
	void timeoutIncludesLastStatusAndBoundedResponseExcerpt() {
		String body = "x".repeat(2_000);
		AssertionError failure = assertThrows(AssertionError.class, () -> PetClinicHttpHarness.waitUntilReady(
				PROBE, Duration.ofMillis(30), Duration.ofMillis(1),
				uri -> new PetClinicHttpHarness.ProbeResponse(429, body)));

		assertTrue(failure.getMessage().contains(PROBE.toString()));
		assertTrue(failure.getMessage().contains("last HTTP status: 429"));
		assertTrue(failure.getMessage().contains("x".repeat(160) + "..."));
		assertTrue(failure.getMessage().length() < 400, "timeout evidence should remain bounded");
	}

	@Test
	void timeoutWithoutResponseIncludesLastConnectionFailure() {
		AssertionError failure = assertThrows(AssertionError.class, () -> PetClinicHttpHarness.waitUntilReady(
				PROBE, Duration.ofMillis(30), Duration.ofMillis(1),
				uri -> { throw new IOException("connection refused"); }));

		assertTrue(failure.getMessage().contains("last connection/IO failure"));
		assertTrue(failure.getMessage().contains("connection refused"));
	}
}
