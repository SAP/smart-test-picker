// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springdatae2e.app;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
class FixtureSeed implements CommandLineRunner {
	private final SampleRepository repository;
	FixtureSeed(SampleRepository repository) { this.repository = repository; }
	@Override public void run(String... args) { repository.save(new SampleEntity("seed")); }
}
