// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springdatae2e.app;

import jakarta.persistence.EntityManager;

import java.util.List;

public class SampleRepositoryImpl implements SampleRepositoryCustom {
	private final EntityManager entityManager;

	public SampleRepositoryImpl(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override public List<SampleEntity> findCachedByName(String name) {
		FixtureCounters.CACHED_EXECUTIONS.incrementAndGet();
		return entityManager.createQuery("select s from SampleEntity s where s.name = :name", SampleEntity.class)
				.setParameter("name", name).getResultList();
	}
	@Override public List<SampleEntity> findAlpha() { return List.of(); }
	@Override public List<SampleEntity> findBeta() { return List.of(); }
	@Override public void failExactly() { throw FixtureCounters.EXPECTED_FAILURE; }
}
