// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package task19.oracle;

import java.lang.reflect.Method;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;

public final class Task19JupiterExtension implements InvocationInterceptor {
    private static String identity(ExtensionContext context) {
        return context.getTestClass().map(Class::getName).orElse("") + "#"
                + context.getTestMethod().map(Method::getName).orElse("<container>");
    }
    private static void invoke(Invocation<Void> invocation, ExtensionContext context, Method method, String phase)
            throws Throwable {
        LifecycleRecorder.record(identity(context), phase + "_START", method.toGenericString());
        try { invocation.proceed(); }
        finally { LifecycleRecorder.record(identity(context), phase + "_END", method.toGenericString()); }
    }
    @Override public void interceptBeforeAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        invoke(invocation, context, method.getExecutable(), "BEFORE_ALL");
    }
    @Override public void interceptBeforeEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        invoke(invocation, context, method.getExecutable(), "BEFORE_EACH");
    }
    @Override public void interceptTestMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        invoke(invocation, context, method.getExecutable(), "TEST_EXECUTION");
    }
    @Override public void interceptAfterEachMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        invoke(invocation, context, method.getExecutable(), "AFTER_EACH");
    }
    @Override public void interceptAfterAllMethod(Invocation<Void> invocation,
            ReflectiveInvocationContext<Method> method, ExtensionContext context) throws Throwable {
        invoke(invocation, context, method.getExecutable(), "AFTER_ALL");
    }
}
