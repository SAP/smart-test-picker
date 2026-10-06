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
	private static final String HEADER = "X-STP-Test-Execution-Id";
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

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
		System.out.println("Readiness probe GET " + probe);
		long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
		IOException lastFailure = null;
		while (System.nanoTime() < deadline) {
			try {
				HttpResponse<String> response = send(probe, null);
				if (response.statusCode() == 200) {
					System.out.println("PetClinic ready at " + base);
					return;
				}
				throw new AssertionError("Readiness probe GET " + probe + " returned HTTP " + response.statusCode()
						+ "; expected HTTP 200. The configured probe endpoint must exist; no fallback is used.");
			} catch (IOException failure) {
				lastFailure = failure;
			}
			Thread.sleep(250);
		}
		throw new AssertionError("PetClinic did not become ready at " + base, lastFailure);
	}

	private static void sendScenario(URI base) throws Exception {
		HttpResponse<String> vets = send(base.resolve("/vets"), "vets-A");
		check(vets.statusCode() == 200, "GET /vets returned " + vets.statusCode());
		check(vets.headers().firstValue("content-type").orElse("").contains("application/json"),
				"GET /vets did not return JSON");
		check(vets.body().contains("vetList"), "GET /vets response did not contain the vet list");

		HttpResponse<String> owner = send(base.resolve("/owners/1"), "owner-B");
		check(owner.statusCode() == 200, "GET /owners/1 returned " + owner.statusCode());
		check(owner.headers().firstValue("content-type").orElse("").contains("text/html"),
				"GET /owners/1 did not return HTML");

		HttpResponse<String> ordinary = send(base.resolve("/vets"), null);
		check(ordinary.statusCode() == 200, "headerless GET /vets returned " + ordinary.statusCode());
		System.out.println("JVM B completed vets-A, owner-B, and headerless GET requests");
	}

	private static HttpResponse<String> send(URI uri, String executionId) throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).GET();
		if (executionId != null) request.header(HEADER, executionId);
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
		check(countOccurrences(json, "\"testExecutionId\":") == 2,
				"expected exactly two attributed requests; headerless request must be absent: " + json);
		System.out.println("PASS: vets-A and owner-B contain their separate PetClinic paths; headerless request has no observation");
	}

	private static void printMethodCounts(String json) {
		Pattern tests = Pattern.compile("\\{\\\"testExecutionId\\\":\\\"([^\\\"]+)\\\",\\\"methods\\\":\\[(.*?)]}");
		Matcher test = tests.matcher(json);
		Pattern method = Pattern.compile("\"(?:\\\\.|[^\"])*\"");
		while (test.find()) {
			Matcher methods = method.matcher(test.group(2));
			int count = 0;
			while (methods.find()) count++;
			System.out.println(test.group(1) + " method count: " + count);
		}
	}

	private static String methodsFor(String json, String id) {
		Pattern observation = Pattern.compile("\\{\\\"testExecutionId\\\":\\\"" + Pattern.quote(id)
				+ "\\\",\\\"methods\\\":\\[(.*?)]}");
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
