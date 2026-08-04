// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springdatae2e.app;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;

@Entity
public class SampleEntity {
	@Id @GeneratedValue
	private Long id;
	private String name;

	protected SampleEntity() {
	}

	public SampleEntity(String name) {
		this.name = name;
	}

	public Long getId() { return id; }
	public String getName() { return name; }
}
