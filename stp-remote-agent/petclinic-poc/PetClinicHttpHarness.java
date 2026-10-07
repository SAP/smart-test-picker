// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standalone JVM B HTTP driver and observation verifier for the PetClinic POC. */
public final class PetClinicHttpHarness {
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(90);
	private static final Duration READINESS_RETRY_DELAY = Duration.ofMillis(250);
	private static final int MAX_ERROR_BODY_CHARS = 160;

	private PetClinicHttpHarness() { }

	public static void main(String[] args) throws Exception {
		if (args.length < 2) throw new IllegalArgumentException(
				"usage: wait --probe-path <path> <base-url> | run <base-url> | verify <observations.json>");
		switch (args[0]) {
			case "wait" -> {
				if (args.length != 4 || !"--probe-path".equals(args[1])) {
					throw new IllegalArgumentException("usage: wait --probe-path <path> <base-url>");
				}
				waitUntilReady(URI.create(args[3]), args[2]);
			}
			case "run" -> sendScenario(URI.create(args[1]));
			case "verify" -> verify(Path.of(args[1]));
			default -> throw new IllegalArgumentException("unknown mode: " + args[0]);
		}
	}

	private static void waitUntilReady(URI base, String probePath) throws Exception {
		if (!probePath.startsWith("/")) throw new IllegalArgumentException("probe path must start with '/': " + probePath);
		URI probe = base.resolve(probePath);
		waitUntilReady(probe, READINESS_TIMEOUT, READINESS_RETRY_DELAY, PetClinicHttpHarness::sendProbe);
		System.out.println("PetClinic ready at " + base);
	}

	static void waitUntilReady(URI probe, Duration timeout, Duration retryDelay, ProbeClient client)
			throws Exception {
		System.out.println("Readiness probe GET " + probe);
		long deadline = System.nanoTime() + timeout.toNanos();
		IOException lastFailure = null;
		ProbeResponse lastResponse = null;
		while (System.nanoTime() < deadline) {
			try {
				lastResponse = client.get(probe);
				if (lastResponse.statusCode() == 200) return;
			} catch (IOException failure) {
				lastFailure = failure;
			}
			Thread.sleep(retryDelay.toMillis());
		}
		String message = "PetClinic readiness timed out for configured probe GET " + probe;
		if (lastResponse != null) {
			message += "; last HTTP status: " + lastResponse.statusCode();
			String excerpt = bodyExcerpt(lastResponse.body());
			if (!excerpt.isEmpty()) message += "; response body excerpt: " + excerpt;
		} else if (lastFailure != null) {
			message += "; last connection/IO failure: " + lastFailure;
		}
		throw new AssertionError(message, lastFailure);
	}

	private static ProbeResponse sendProbe(URI uri) throws IOException, InterruptedException {
		HttpResponse<String> response = send(uri, null, null);
		return new ProbeResponse(response.statusCode(), response.body());
	}

	private static String bodyExcerpt(String body) {
		if (body == null || body.isEmpty()) return "";
		String normalized = body.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= MAX_ERROR_BODY_CHARS) return '"' + normalized + '"';
		return '"' + normalized.substring(0, MAX_ERROR_BODY_CHARS) + "...\"";
	}

	@FunctionalInterface
	interface ProbeClient {
		ProbeResponse get(URI uri) throws IOException, InterruptedException;
	}

	record ProbeResponse(int statusCode, String body) { }

	private static void sendScenario(URI base) throws Exception {
		HttpResponse<String> vets = send(base.resolve("/vets"), "manual-poc-suite", "vets-A");
		check(vets.statusCode() == 200, "GET /vets returned " + vets.statusCode());
		check(vets.headers().firstValue("content-type").orElse("").contains("application/json"),
				"GET /vets did not return JSON");
		check(vets.body().contains("vetList"), "GET /vets response did not contain the vet list");

		HttpResponse<String> owner = send(base.resolve("/owners/1"), "manual-poc-suite", "owner-B");
		check(owner.statusCode() == 200, "GET /owners/1 returned " + owner.statusCode());
		check(owner.headers().firstValue("content-type").orElse("").contains("text/html"),
				"GET /owners/1 did not return HTML");

		HttpResponse<String> ordinary = send(base.resolve("/vets"), null, null);
		check(ordinary.statusCode() == 200, "headerless GET /vets returned " + ordinary.statusCode());
		System.out.println("JVM B completed vets-A, owner-B, and headerless GET requests");
	}

	private static HttpResponse<String> send(URI uri, String suiteId, String testId) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
		if (testId != null) request.header("baggage", "stp.test.suite.id=" + suiteId + ",stp.test.id=" + testId
				+ ",stp.request.id=" + java.util.UUID.randomUUID());
		return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static void verify(Path output) throws IOException {
		String json = Files.readString(output);
		String vets = methodsFor(json, "vets-A");
		String owner = methodsFor(json, "owner-B");
		printMethodCounts(json);
		check(vets.contains("org.springframework.samples.petclinic.vet.VetController#showResourcesVetList"),
				"vets-A did not capture the VetController JSON route: " + vets);
		check(owner.contains("org.springframework.samples.petclinic.owner.OwnerController#showOwner"),
				"owner-B did not capture the OwnerController details route: " + owner);
		check(!vets.contains("org.springframework.samples.petclinic.owner.OwnerController#"),
				"owner methods leaked into vets-A: " + vets);
		check(!owner.contains("org.springframework.samples.petclinic.vet.VetController#"),
				"vet methods leaked into owner-B: " + owner);
		check(countOccurrences(json, "\"testId\":") == 2,
				"expected exactly two attributed requests; headerless request must be absent: " + json);
		System.out.println("PASS: vets-A and owner-B contain their separate PetClinic paths; headerless request has no observation");
	}

	private static void printMethodCounts(String json) {
		Pattern tests = Pattern.compile("\\{\\\"testSuiteId\\\":\\\"([^\\\"]+)\\\",\\\"testId\\\":\\\"([^\\\"]+)\\\",\\\"requestId\\\":\\\"([^\\\"]+)\\\",\\\"methods\\\":\\[(.*?)]}");
		Matcher test = tests.matcher(json);
		Pattern method = Pattern.compile("\"(?:\\\\.|[^\"])*\"");
		while (test.find()) {
			Matcher methods = method.matcher(test.group(4));
			int count = 0;
			while (methods.find()) count++;
			System.out.println(test.group(1) + "/" + test.group(2) + "/" + test.group(3) + " method count: " + count);
		}
	}

	private static String methodsFor(String json, String id) {
		Pattern observation = Pattern.compile("\\{\\\"testSuiteId\\\":\\\"[^\\\"]+\\\",\\\"testId\\\":\\\"" + Pattern.quote(id)
				+ "\\\",\\\"requestId\\\":\\\"[^\\\"]+\\\",\\\"methods\\\":\\[(.*?)]}");
		Matcher matcher = observation.matcher(json);
		check(matcher.find(), "missing observation for " + id + " in " + json);
		return matcher.group(1);
	}

	private static int countOccurrences(String value, String needle) {
		int count = 0;
		for (int index = 0; (index = value.indexOf(needle, index)) >= 0; index += needle.length()) count++;
		return count;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
