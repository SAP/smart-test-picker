// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package task19.oracle;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

public final class Task19LauncherListener implements TestExecutionListener {
    @Override public void executionStarted(TestIdentifier id) {
        if (id.isTest()) LifecycleRecorder.record(identity(id), "LEAF_OWNERSHIP_START", id.getUniqueId());
    }
    @Override public void executionFinished(TestIdentifier id, TestExecutionResult result) {
        if (id.isTest()) LifecycleRecorder.record(identity(id), "LEAF_OWNERSHIP_END", result.getStatus().name());
    }
    private static String identity(TestIdentifier id) {
        return id.getSource().filter(MethodSource.class::isInstance).map(MethodSource.class::cast)
                .map(source -> source.getClassName() + "#" + source.getMethodName()).orElse(id.getDisplayName());
    }
}
