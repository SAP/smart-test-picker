// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class JoinRemoteCommandTest {
	@TempDir Path temp;

	@Test void joinsOneFragmentAndPreservesAllIdentityAndSourceFields() throws Exception {
		Path input = fragment("one.json", fragment("svc-a", "pod-a", "rev-1", "request-a", "m1", "m2"));
		Path output = temp.resolve("joined.json");
		assertEquals(0, run(output, input));
		JsonObject observation = observations(output).get(0).getAsJsonObject();
		assertEquals("suite", observation.get("testSuiteId").getAsString());
		assertEquals("test", observation.get("testId").getAsString());
		assertEquals("request-a", observation.get("requestId").getAsString());
		assertEquals("svc-a", observation.getAsJsonObject("source").get("serviceId").getAsString());
		assertEquals("pod-a", observation.getAsJsonObject("source").get("instanceId").getAsString());
		assertEquals("rev-1", observation.getAsJsonObject("source").get("revision").getAsString());
	}

	@Test void duplicateAndOverlappingFragmentsUnionMethodsOnlyForTheSameSourceTuple() throws Exception {
		Path a = fragment("a.json", fragment("svc", "pod", "rev-1", "req", "m1", "m2"));
		Path duplicate = fragment("duplicate.json", fragment("svc", "pod", "rev-1", "req", "m2", "m3"));
		Path otherInstance = fragment("other.json", fragment("svc", "pod-2", "rev-1", "req", "other"));
		Path otherRevision = fragment("revision.json", fragment("svc", "pod", "rev-2", "req", "changed"));
		Path output = temp.resolve("joined.json");
		assertEquals(0, run(output, a, duplicate, a, otherInstance, otherRevision));
		JsonArray rows = observations(output);
		assertEquals(3, rows.size());
		assertEquals(List.of("m1", "m2", "m3"), methods(rows.get(0).getAsJsonObject()));
		assertEquals("rev-2", rows.get(1).getAsJsonObject().getAsJsonObject("source").get("revision").getAsString());
		assertEquals("pod-2", rows.get(2).getAsJsonObject().getAsJsonObject("source").get("instanceId").getAsString());
	}

	@Test void reorderedInputsProduceByteIdenticalOutput() throws Exception {
		Path a = fragment("a.json", fragment("svc-b", "pod-2", "rev", "req-2", "z"));
		Path b = fragment("b.json", fragment("svc-a", "pod-1", "rev", "req-1", "a"));
		Path first = temp.resolve("first.json");
		Path second = temp.resolve("second.json");
		assertEquals(0, run(first, a, b));
		assertEquals(0, run(second, b, a, a));
		assertEquals(Files.readString(first), Files.readString(second));
	}

	@Test void rejectsMalformedUnsupportedAndMissingSourceInputsWithFileName() throws Exception {
		Path malformed = temp.resolve("malformed.json"); Files.writeString(malformed, "{broken");
		Path trailing = temp.resolve("trailing.json"); Files.writeString(trailing, "{\"schemaVersion\":2,\"source\":{},\"requests\":[]} garbage");
		Path unsupported = fragment("unsupported.json", "{\"schemaVersion\":3,\"source\":{},\"requests\":[]}");
		Path missing = fragment("missing.json", "{\"schemaVersion\":2,\"requests\":[]}");
		for (Path invalid : List.of(malformed, trailing, unsupported, missing)) {
			String error = runFailure(invalid);
			assertTrue(error.contains(invalid.toString()), error);
		}
	}

	@Test void rejectsInvalidRequestIdentitiesAndMethodValues() throws Exception {
		Path partial = fragment("partial.json", "{\"schemaVersion\":2,\"source\":{\"serviceId\":\"s\",\"instanceId\":\"i\",\"revision\":\"r\"},\"requests\":[{\"testSuiteId\":\"s\",\"testId\":\"t\",\"methods\":[]}]}");
		Path control = fragment("control.json", fragment("svc", "pod", "rev", "bad\nrequest", "m"));
		Path badMethod = fragment("method.json", "{\"schemaVersion\":2,\"source\":{\"serviceId\":\"s\",\"instanceId\":\"i\",\"revision\":\"r\"},\"requests\":[{\"testSuiteId\":\"s\",\"testId\":\"t\",\"requestId\":\"r\",\"methods\":[\" \"]}]}");
		for (Path invalid : List.of(partial, control, badMethod)) assertTrue(runFailure(invalid).contains(invalid.toString()));
	}

	@Test void unreadableInputAndUnwritableOutputFailWithRelevantPath() throws Exception {
		Path absent = temp.resolve("absent.json");
		assertTrue(runFailure(absent).contains(absent.toString()));
		Path valid = fragment("valid.json", fragment("svc", "pod", "rev", "req", "m"));
		Path blocker = temp.resolve("not-a-directory"); Files.writeString(blocker, "x");
		Path output = blocker.resolve("joined.json");
		CommandLine command = new CommandLine(new JoinRemoteCommand());
		assertEquals(1, command.execute("--input", valid.toString(), "--output", output.toString()));
	}

	@Test void cliAcceptsMultiplePathsAndDoesNotOverwriteAnInputPath() throws Exception {
		Path a = fragment("a.json", fragment("svc-a", "pod", "rev", "req-a", "a"));
		Path b = fragment("b.json", fragment("svc-b", "pod", "rev", "req-b", "b"));
		Path output = temp.resolve("cli.json");
		assertEquals(0, new CommandLine(new JoinRemoteCommand()).execute("--input", a.toString(), b.toString(), "--output", output.toString()));
		String original = Files.readString(a);
		assertEquals(1, new CommandLine(new JoinRemoteCommand()).execute("--input", a.toString(), "--output", a.toString()));
		assertEquals(original, Files.readString(a));
	}

	private String runFailure(Path input) throws Exception {
		var bytes = new java.io.ByteArrayOutputStream();
		java.io.PrintStream previous = System.err;
		int status;
		try {
			System.setErr(new java.io.PrintStream(bytes));
			status = new CommandLine(new JoinRemoteCommand()).execute("--input", input.toString(), "--output", temp.resolve("failure.json").toString());
		} finally { System.setErr(previous); }
		assertEquals(1, status, bytes.toString(java.nio.charset.StandardCharsets.UTF_8));
		return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
	}

	private int run(Path output, Path... inputs) {
		List<String> args = new java.util.ArrayList<>(List.of("--input"));
		for (Path input : inputs) args.add(input.toString());
		args.add("--output"); args.add(output.toString());
		return new CommandLine(new JoinRemoteCommand()).execute(args.toArray(String[]::new));
	}

	private Path fragment(String filename, String body) throws Exception {
		Path file = temp.resolve(filename); Files.writeString(file, body); return file;
	}

	private Path fragment(String filename, JsonObject request) throws Exception {
		return fragment(filename, new com.google.gson.GsonBuilder().create().toJson(request));
	}

	private JsonObject fragment(String service, String instance, String revision, String requestId, String... methods) {
		JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 2);
		JsonObject source = new JsonObject(); source.addProperty("serviceId", service); source.addProperty("instanceId", instance); source.addProperty("revision", revision); root.add("source", source);
		JsonObject request = new JsonObject(); request.addProperty("testSuiteId", "suite"); request.addProperty("testId", "test"); request.addProperty("requestId", requestId);
		JsonArray methodArray = new JsonArray(); for (String method : methods) methodArray.add(method); request.add("methods", methodArray);
		root.add("requests", new JsonArray()); root.getAsJsonArray("requests").add(request); return root;
	}

	private JsonObject root(JsonObject request) {
		JsonObject root = new JsonObject(); root.addProperty("schemaVersion", 2);
		JsonObject source = new JsonObject(); source.addProperty("serviceId", "svc"); source.addProperty("instanceId", "pod"); source.addProperty("revision", "rev"); root.add("source", source);
		JsonArray requests = new JsonArray(); requests.add(request); root.add("requests", requests); return root;
	}

	private JsonArray observations(Path file) throws Exception {
		return JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("observations");
	}

	private List<String> methods(JsonObject observation) {
		List<String> methods = new java.util.ArrayList<>(); observation.getAsJsonArray("methods").forEach(method -> methods.add(method.getAsString())); return methods;
	}
}
