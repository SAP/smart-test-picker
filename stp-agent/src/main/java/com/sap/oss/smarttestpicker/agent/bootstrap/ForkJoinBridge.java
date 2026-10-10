// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent.bootstrap;

/** The only STP classes loaded by bootstrap: no runtime, ASM or application dependencies. */
public final class ForkJoinBridge {
	public interface Handler {
		Object enter(int operation, Object task);
		void exit(int operation, Object ticket, Throwable failure);
		void error(Throwable failure);
	}
	private static volatile Handler handler;
	private static final ThreadLocal<Boolean> busy = new ThreadLocal<>();
	private ForkJoinBridge() {}
	public static void install(Handler value) { handler = value; }
	public static Object enter(int operation, Object task) {
		Handler active = handler;
		if (active == null || Boolean.TRUE.equals(busy.get())) return null;
		busy.set(true);
		try { return active.enter(operation, task); }
		catch (Throwable failure) { report(active, failure); return null; }
		finally { busy.remove(); }
	}
	public static void exit(int operation, Object ticket, Throwable failure) {
		Handler active = handler;
		if (active == null || ticket == null || Boolean.TRUE.equals(busy.get())) return;
		busy.set(true);
		try { active.exit(operation, ticket, failure); }
		catch (Throwable problem) { report(active, problem); }
		finally { busy.remove(); }
	}
	private static void report(Handler active, Throwable failure) {
		try { active.error(failure); } catch (Throwable ignored) { /* never change task semantics */ }
	}
}
