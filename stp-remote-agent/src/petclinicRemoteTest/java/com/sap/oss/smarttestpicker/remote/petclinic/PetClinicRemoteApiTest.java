// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.remote.petclinic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PetClinicRemoteApiTest {
	private static final CyclicBarrier VET_OWNER_BARRIER = new CyclicBarrier(2);

	@Test
	void vetsRequest() throws Exception {
		TestIdentity identity = activeIdentity();
		awaitParallelPair("vetsRequest", identity);
		HttpResponse<String> response = PetClinicTestHttpClient.get("/vets");
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
		assertTrue(response.body().contains("vetList"), "expected the PetClinic vets response");
	}

	@Test
	void ownerRequest() throws Exception {
		TestIdentity identity = activeIdentity();
		awaitParallelPair("ownerRequest", identity);
		assertOwnerResponse(PetClinicTestHttpClient.get("/owners/1"));
	}

	@ParameterizedTest(name = "ownersParameterized")
	@ValueSource(ints = {2, 3, 4})
	void ownersParameterized(int ownerId) throws Exception {
		activeIdentity();
		assertOwnerResponse(PetClinicTestHttpClient.get("/owners/" + ownerId));
	}

	@Test
	void noHttpTest() {
		activeIdentity();
	}

	private static TestIdentity activeIdentity() {
		ClassLoader registryLoader = RuntimeContextRegistry.class.getClassLoader();
		ClassLoader serviceLoader = RuntimeContextService.class.getClassLoader();
		var registered = RuntimeContextRegistry.current();
		assertTrue(registered.isPresent(), "STP runtime registry is empty in the REST test JVM");
		RuntimeContextService service = registered.orElseThrow();
		TestIdentity identity = service.currentTest().orElseThrow(
				() -> new AssertionError("STP runtime has no active TestIdentity"));
		System.out.printf("STP_CONTEXT registryClassLoader=%s serviceClassLoader=%s platformUniqueId=%s%n",
				registryLoader, serviceLoader, identity.platformUniqueId());
		return identity;
	}

	private static void assertOwnerResponse(HttpResponse<String> response) {
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("content-type").orElse("").contains("text/html"));
	}

	private static void awaitParallelPair(String method, TestIdentity identity) throws Exception {
		if (!Boolean.getBoolean("junit.jupiter.execution.parallel.enabled")) return;
		long ready = System.nanoTime();
		System.out.printf("PAIR_BARRIER_READY testMethod=%s testId=%s nanos=%d time=%s%n",
				method, identity.platformUniqueId(), ready, Instant.now());
		VET_OWNER_BARRIER.await(20, TimeUnit.SECONDS);
		System.out.printf("PAIR_BARRIER_RELEASE testMethod=%s testId=%s nanos=%d time=%s%n",
				method, identity.platformUniqueId(), System.nanoTime(), Instant.now());
	}
}
