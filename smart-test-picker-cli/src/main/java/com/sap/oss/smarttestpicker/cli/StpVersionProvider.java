// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.cli;

import picocli.CommandLine.IVersionProvider;

public final class StpVersionProvider implements IVersionProvider {
	@Override
	public String[] getVersion() {
		String version = StpVersionProvider.class.getPackage().getImplementationVersion();
		return new String[] { version == null ? "development" : version };
	}
}
