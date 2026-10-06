// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.springremote;

import com.sap.oss.smarttestpicker.remote.RemoteTestContext;

/** Captures direct runtime evidence, including the ID and physical thread at the app boundary. */
public class SpringMvcRepository {
	public String hit(String scenario) {
		String id = RemoteTestContext.currentId();
		String event = scenario + "|" + (id == null ? "<none>" : id) + "|" + Thread.currentThread().getName()
				+ "|" + Thread.currentThread().getId() + "|" + System.nanoTime();
		System.out.println("SPRING_EVENT:" + event);
		return event;
	}
}
