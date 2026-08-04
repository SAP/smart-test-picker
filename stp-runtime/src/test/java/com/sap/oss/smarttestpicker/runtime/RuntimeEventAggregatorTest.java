// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.runtime;

import com.sap.oss.smarttestpicker.runtime.model.Certainty;
import com.sap.oss.smarttestpicker.runtime.model.EndpointEvent;
import com.sap.oss.smarttestpicker.runtime.model.EntityEvent;
import com.sap.oss.smarttestpicker.runtime.model.Evidence;
import com.sap.oss.smarttestpicker.runtime.model.EvidenceSource;
import com.sap.oss.smarttestpicker.runtime.model.MethodHitEvent;
import com.sap.oss.smarttestpicker.runtime.model.MethodIdentity;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryInvocationEvent;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryKind;
import com.sap.oss.smarttestpicker.runtime.model.RepositoryOutcome;
import com.sap.oss.smarttestpicker.runtime.model.SpringBeanEvent;
import com.sap.oss.smarttestpicker.runtime.model.TableAccess;
import com.sap.oss.smarttestpicker.runtime.model.TableEvent;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedEvent;
import com.sap.oss.smarttestpicker.runtime.model.UnattributedReason;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.sap.oss.smarttestpicker.runtime.model.RepositoryKind.SPRING_DATA_PROXY;

class RuntimeEventAggregatorTest {
	private static final Evidence ASM = new Evidence(EvidenceSource.ASM_METHOD_ENTRY, Certainty.OBSERVED);
	private static final Evidence SPRING_DATA = new Evidence(EvidenceSource.SPRING_DATA, Certainty.OBSERVED);
	private static final RuntimeJsonSerializer JSON = new RuntimeJsonSerializer();

	@Test
	void serializesControllerShapeToGoldenJson() throws IOException {
		RuntimeEventAggregator aggregator = controllerFixture();
		String expected;
		try (var stream = getClass().getResourceAsStream("/golden/owner-controller-success.json")) {
			expected = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		assertEquals(expected, JSON.serialize(aggregator));
	}

	@Test
	void deduplicatesMethodHits() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		TestIdentity test = test("test-a");
		aggregator.record(test, method("example.Owner", "save", "()V"));
		aggregator.record(test, method("example.Owner", "save", "()V"));

		String json = JSON.serialize(aggregator);
		assertEquals(1, occurrences(json, "example.Owner#save()V"));
		assertTrue(json.contains("\"uniqueMethodHits\": 1"));
	}

	@Test
	void countsRepeatedMethodHits() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		TestIdentity test = test("test-a");
		for (int i = 0; i < 3; i++) aggregator.record(test, method("example.Owner", "save", "()V"));

		String json = JSON.serialize(aggregator);
		assertTrue(json.contains("\"count\":3"));
		assertTrue(json.contains("\"rawMethodHits\": 3"));
		assertTrue(json.contains("\"uniqueMethodHits\": 1"));
	}

	@Test
	void keepsMappedAndObservedTablesSeparate() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		TestIdentity test = test("test-a");
		aggregator.record(test, new TableEvent("owners", TableAccess.MAPPED,
				new Evidence(EvidenceSource.STATIC_MAPPING, Certainty.INFERRED)));
		aggregator.record(test, new TableEvent("owners", TableAccess.OBSERVED_WRITE,
				new Evidence(EvidenceSource.HIBERNATE_SQL, Certainty.OBSERVED)));

		String json = JSON.serialize(aggregator);
		int mapped = json.indexOf("\"mapped\"");
		int observed = json.indexOf("\"observed\"");
		assertTrue(mapped < json.indexOf("\"access\":\"MAPPED\"", mapped));
		assertTrue(observed < json.indexOf("\"access\":\"OBSERVED_WRITE\"", observed));
		assertEquals(1, occurrences(json, "\"access\":\"MAPPED\""));
		assertEquals(1, occurrences(json, "\"access\":\"OBSERVED_WRITE\""));
	}

	@Test
	void distinguishesRepositoryOverloadsByJvmDescriptor() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		TestIdentity test = test("test-a");
		aggregator.record(test, repository("findById", "(I)Ljava/util/Optional;", RepositoryOutcome.SUCCEEDED));
		aggregator.record(test, repository("findById", "(Ljava/lang/Integer;)Lexample/Owner;",
				RepositoryOutcome.SUCCEEDED));

		String json = JSON.serialize(aggregator);
		assertTrue(json.contains("(I)Ljava/util/Optional;"));
		assertTrue(json.contains("(Ljava/lang/Integer;)Lexample/Owner;"));
		assertEquals(2, occurrences(json, "\"methodName\":\"findById\""));
	}

	@Test
	void springDataRepositoryKindIsRequiredAndSerialized() {
		assertThrows(NullPointerException.class, () -> new RepositoryInvocationEvent(null,
				"example.OwnerRepository", "ownerRepository", "save", "(Ljava/lang/Object;)Ljava/lang/Object;",
				"example.Owner", RepositoryOutcome.SUCCEEDED, SPRING_DATA));
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		aggregator.record(test("test-a"), repository("save", "(Ljava/lang/Object;)Ljava/lang/Object;",
				RepositoryOutcome.SUCCEEDED));
		assertTrue(JSON.serialize(aggregator).contains("\"repositoryKind\":\"SPRING_DATA_PROXY\""));
	}

	@Test
	void equalSpringDataRepositoryEventsDeduplicateAndCountRepetitions() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		RepositoryInvocationEvent event = repository("save", "(Ljava/lang/Object;)Ljava/lang/Object;",
				RepositoryOutcome.SUCCEEDED);
		aggregator.record(test("test-a"), event);
		aggregator.record(test("test-a"), event);

		String json = JSON.serialize(aggregator);
		assertEquals(1, occurrences(json, "\"repositoryKind\":\"SPRING_DATA_PROXY\""));
		assertTrue(json.contains("\"count\":2"));
	}

	@Test
	void repositoryIdentityAndSortContractIncludeKindAndEveryDependencyField() {
		RepositoryInvocationEvent event = repository("save", "(Ljava/lang/Object;)Ljava/lang/Object;",
				RepositoryOutcome.SUCCEEDED);
		assertTrue(java.util.Arrays.stream(RepositoryInvocationEvent.class.getRecordComponents())
				.anyMatch(component -> component.getName().equals("repositoryKind")
						&& component.getType() == RepositoryKind.class));
		assertEquals("SPRING_DATA_PROXY\u0000example.OwnerRepository\u0000ownerRepository\u0000save"
				+ "\u0000(Ljava/lang/Object;)Ljava/lang/Object;\u0000example.Owner\u0000SUCCEEDED"
				+ "\u0000SPRING_DATA\u0000OBSERVED", RuntimeJsonSerializer.repositoryKey(event));
		// RepositoryInvocationEvent is a record, so every record component above is
		// part of equality/hash identity. A future RepositoryKind value therefore
		// cannot compare equal or merge without adding an unsupported value today.
	}

	@Test
	void recordsStartupInSeparateUnattributedBucket() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		aggregator.recordUnattributed(new UnattributedEvent(UnattributedReason.STARTUP, "TABLE", "owners",
				new Evidence(EvidenceSource.HIBERNATE_SQL, Certainty.OBSERVED)));

		String json = JSON.serialize(aggregator);
		assertTrue(json.contains("\"tests\": []"));
		assertTrue(json.contains("\"reason\":\"STARTUP\""));
	}

	@Test
	void convertsAnEventAfterFinishToLateEvent() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		TestIdentity test = test("test-a");
		aggregator.finishTest(test);
		aggregator.record(test, method("example.Owner", "late", "()V"));

		String json = JSON.serialize(aggregator);
		assertTrue(json.contains("\"reason\":\"LATE_EVENT\""));
		assertTrue(json.contains("\"eventIdentity\":\"example.Owner#late()V\""));
		assertTrue(json.contains("\"methods\": []"));
	}

	@Test
	void keepsTwoTestsFreeFromCrossContamination() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		TestIdentity first = test("test-a");
		TestIdentity second = test("test-b");
		aggregator.beginTest(first);
		aggregator.beginTest(second);
		aggregator.record(first, method("example.First", "onlyFirst", "()V"));
		aggregator.record(second, method("example.Second", "onlySecond", "()V"));

		String json = JSON.serialize(aggregator);
		String firstSection = json.substring(json.indexOf("\"testId\": \"test-a\""),
				json.indexOf("\"testId\": \"test-b\""));
		String secondSection = json.substring(json.indexOf("\"testId\": \"test-b\""));
		assertTrue(firstSection.contains("example.First#onlyFirst()V"));
		assertFalse(firstSection.contains("example.Second#onlySecond()V"));
		assertTrue(secondSection.contains("example.Second#onlySecond()V"));
		assertFalse(secondSection.contains("example.First#onlyFirst()V"));
	}

	@Test
	void outputOrderingDoesNotDependOnInsertionOrder() {
		RuntimeEventAggregator forward = new RuntimeEventAggregator("run-1", "jvm-1");
		RuntimeEventAggregator reverse = new RuntimeEventAggregator("run-1", "jvm-1");
		TestIdentity a = test("a");
		TestIdentity z = test("z");
		forward.beginTest(a);
		forward.beginTest(z);
		reverse.beginTest(z);
		reverse.beginTest(a);
		forward.record(a, method("z.Type", "z", "()V"));
		forward.record(a, method("a.Type", "a", "()V"));
		forward.record(a, repository("z", "()V", RepositoryOutcome.SUCCEEDED));
		forward.record(a, repository("a", "()V", RepositoryOutcome.SUCCEEDED));
		reverse.record(a, method("a.Type", "a", "()V"));
		reverse.record(a, method("z.Type", "z", "()V"));
		reverse.record(a, repository("a", "()V", RepositoryOutcome.SUCCEEDED));
		reverse.record(a, repository("z", "()V", RepositoryOutcome.SUCCEEDED));

		assertEquals(JSON.serialize(forward), JSON.serialize(reverse));
	}

	@Test
	void representsFailedRepositoryInvocation() {
		RuntimeEventAggregator aggregator = aggregatorWithTest("test-a");
		aggregator.record(test("test-a"), repository("save", "(Lexample/Owner;)Lexample/Owner;",
				RepositoryOutcome.FAILED));

		String json = JSON.serialize(aggregator);
		assertTrue(json.contains("\"outcome\":\"FAILED\""));
		assertTrue(json.contains("\"jvmDescriptor\":\"(Lexample/Owner;)Lexample/Owner;\""));
	}

	private static RuntimeEventAggregator controllerFixture() {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		TestIdentity test = new TestIdentity(
				"[engine:junit-jupiter]/[class:org.springframework.samples.petclinic.owner.OwnerControllerTests]/[method:processCreationFormSuccess()]",
				"org.springframework.samples.petclinic.owner.OwnerControllerTests#processCreationFormSuccess",
				"org.springframework.samples.petclinic.owner.OwnerControllerTests", "processCreationFormSuccess",
				"junit-jupiter", "run-1", "jvm-1");
		aggregator.beginTest(test);
		String ownerController = "org.springframework.samples.petclinic.owner.OwnerController";
		MethodIdentity handler = new MethodIdentity(ownerController, "processCreationForm",
				"(Lorg/springframework/ui/ModelMap;Lorg/springframework/samples/petclinic/owner/Owner;Lorg/springframework/validation/BindingResult;)Ljava/lang/String;");
		aggregator.record(test, new MethodHitEvent(new MethodIdentity(ownerController, "findOwner",
				"(Ljava/lang/Integer;)Lorg/springframework/samples/petclinic/owner/Owner;"), ASM));
		aggregator.record(test, new MethodHitEvent(new MethodIdentity(ownerController, "setAllowedFields",
				"(Lorg/springframework/web/bind/WebDataBinder;)V"), ASM));
		aggregator.record(test, new MethodHitEvent(handler, ASM));
		aggregator.record(test, new SpringBeanEvent("ownerController", ownerController,
				new Evidence(EvidenceSource.SPRING_BEAN, Certainty.OBSERVED)));
		aggregator.record(test, new EndpointEvent("POST", "/owners/new", handler,
				new Evidence(EvidenceSource.SPRING_MVC, Certainty.OBSERVED)));
		aggregator.record(test, new RepositoryInvocationEvent(SPRING_DATA_PROXY,
				"org.springframework.samples.petclinic.owner.OwnerRepository", "ownerRepository", "save",
				"(Lorg/springframework/samples/petclinic/owner/Owner;)Lorg/springframework/samples/petclinic/owner/Owner;",
				"org.springframework.samples.petclinic.owner.Owner", RepositoryOutcome.SUCCEEDED, SPRING_DATA));
		aggregator.record(test, new EntityEvent("org.springframework.samples.petclinic.owner.Owner",
				new Evidence(EvidenceSource.SPRING_DATA, Certainty.INFERRED)));
		aggregator.record(test, new TableEvent("owners", TableAccess.MAPPED,
				new Evidence(EvidenceSource.STATIC_MAPPING, Certainty.INFERRED)));
		aggregator.finishTest(test);
		return aggregator;
	}

	private static RuntimeEventAggregator aggregatorWithTest(String id) {
		RuntimeEventAggregator aggregator = new RuntimeEventAggregator("run-1", "jvm-1");
		aggregator.beginTest(test(id));
		return aggregator;
	}

	private static TestIdentity test(String id) {
		return new TestIdentity(id, id, "example.Test", id, "junit-jupiter", "run-1", "jvm-1");
	}

	private static MethodHitEvent method(String type, String name, String descriptor) {
		return new MethodHitEvent(new MethodIdentity(type, name, descriptor), ASM);
	}

	private static RepositoryInvocationEvent repository(String method, String descriptor,
			RepositoryOutcome outcome) {
		return new RepositoryInvocationEvent(SPRING_DATA_PROXY, "example.OwnerRepository", "ownerRepository", method, descriptor,
				"example.Owner", outcome, SPRING_DATA);
	}

	private static int occurrences(String value, String needle) {
		return (value.length() - value.replace(needle, "").length()) / needle.length();
	}
}
