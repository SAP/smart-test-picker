// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springdatae2e.app;

import java.util.List;

public interface SampleRepositoryCustom {
	List<SampleEntity> findCachedByName(String name);
	List<SampleEntity> findAlpha();
	List<SampleEntity> findBeta();
	void failExactly();
}
