<!--
SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
SPDX-License-Identifier: Apache-2.0
-->

# STP ASM runtime

`stp-runtime` is a framework-neutral internal runtime used by the ASM mapping agent. It owns logical test context, method-hit aggregation, context capture/attach/restore, late-event quarantine, unattributed-event accounting, and deterministic internal serialization.

It has no Spring or build-tool dependency and is bundled into `stp-agent`; it is not a separate user-facing release artifact.
