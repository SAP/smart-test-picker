// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0

/** Reflection fixture for the deliberately ambiguous default-package parameter. */
public class DefaultPackageParameterFixture {
    public Foo works(Foo value) {
        return value;
    }

    public Foo[] arrays(Foo[] values) {
        return values;
    }
}

class Foo {}
