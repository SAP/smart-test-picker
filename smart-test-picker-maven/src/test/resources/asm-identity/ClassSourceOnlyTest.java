// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package a;

@org.junit.runner.RunWith(ClassSourceOnlyTest.RuleRunner.class)
public class ClassSourceOnlyTest {
    public static final String architecture_rule = "rule";
    public static class RuleRunner extends org.junit.runner.Runner {
        private final org.junit.runner.Description root;
        private final org.junit.runner.Description rule;
        public RuleRunner(Class<?> type) {
            root = org.junit.runner.Description.createSuiteDescription(type);
            rule = org.junit.runner.Description.createTestDescription(type, "architecture_rule");
            root.addChild(rule);
        }
        @Override
        public org.junit.runner.Description getDescription() { return root; }
        @Override
        public void run(org.junit.runner.notification.RunNotifier notifier) {
            notifier.fireTestStarted(rule);
            try { org.junit.Assert.assertEquals(1, new AValue().value()); }
            catch (Throwable failure) {
                notifier.fireTestFailure(new org.junit.runner.notification.Failure(rule, failure));
            } finally { notifier.fireTestFinished(rule); }
        }
    }
}
