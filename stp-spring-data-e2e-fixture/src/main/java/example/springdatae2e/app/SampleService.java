// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springdatae2e.app;

import org.springframework.stereotype.Service;

@Service
public class SampleService {
	private final SampleRepository repository;
	public SampleService(SampleRepository repository) { this.repository = repository; }
	public void cachedLookupTwice() {
		repository.findCachedByName("seed");
		repository.findCachedByName("seed");
	}
	public void failRepository() { repository.failExactly(); }
	public void alpha() { repository.findAlpha(); }
	public void beta() { repository.findBeta(); }
}
