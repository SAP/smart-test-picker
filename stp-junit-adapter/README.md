<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP JUnit lifecycle listener

This internal module contains only `StpRuntimeTestExecutionListener` and its service-loader registration. The listener opens and closes logical test ownership around JUnit Platform leaf executions so ASM method hits are attributed to the correct test.

The listener is bundled into `stp-agent` and is not intended as a separate user-facing release artifact.
