// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.agent.bootstrap.ForkJoinBridge;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.jar.*;

/** Installs method-body advice in two verified JDK classes; never attaches to another JVM. */
final class ForkJoinBootstrap {
	private ForkJoinBootstrap() {}
	static void install(Instrumentation instrumentation, Consumer<String> errors) {
		try {
			if (!instrumentation.isRetransformClassesSupported())
				throw new IllegalStateException("retransformation unavailable");
			var path = Files.createTempFile("stp-forkjoin-bootstrap-", ".jar");
			path.toFile().deleteOnExit();
			try (var out = new JarOutputStream(Files.newOutputStream(path))) {
				for (String suffix : List.of("", "$Handler")) {
					String resource = "com/sap/oss/smarttestpicker/agent/bootstrap/ForkJoinBridge" + suffix + ".class";
					out.putNextEntry(new JarEntry(resource));
					try (var in = ForkJoinBootstrap.class.getClassLoader().getResourceAsStream(resource)) {
						if (in == null) throw new IllegalStateException("missing " + resource);
						in.transferTo(out);
					}
					out.closeEntry();
				}
			}
			instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(path.toFile()));
			Class<?> bridge = Class.forName("com.sap.oss.smarttestpicker.agent.bootstrap.ForkJoinBridge", true, null);
			instrumentation.redefineModule(Object.class.getModule(), Set.of(bridge.getModule()),
					Map.of(), Map.of(), Set.of(), Map.of());
			var transformer = new ForkJoinBootstrapTransformer(errors);
			instrumentation.addTransformer(transformer, true);
			instrumentation.retransformClasses(ForkJoinTask.class, ForkJoinPool.class);
			if (!transformer.verified()) throw new IllegalStateException("JDK ForkJoin boundary unverified");
			// Load the adapter only after the helper is available to bootstrap.
			Adapter.install(errors);
		} catch (Exception failure) {
			errors.accept("forkjoin-bootstrap-incomplete:" + failure);
		}
	}
	private static final class Adapter implements ForkJoinBridge.Handler {
		private final Consumer<String> errors;
		private Adapter(Consumer<String> errors) { this.errors = errors; }
		static void install(Consumer<String> errors) { ForkJoinBridge.install(new Adapter(errors)); }
		public Object enter(int op, Object task) {
			var service = RuntimeContextRegistry.current().orElse(null);
			if (service == null) return null;
			ForkJoinTask<?> forkJoin = (ForkJoinTask<?>) task;
			return switch (op) {
				case 0 -> service.beginForkJoinSubmission(forkJoin);
				case 1 -> service.openForkJoinExecution(forkJoin);
				case 2 -> service.beginForkJoinReset(forkJoin);
				default -> throw new IllegalArgumentException("operation " + op);
			};
		}
		public void exit(int op, Object ticket, Throwable failure) {
			var service = RuntimeContextRegistry.current().orElse(null);
			if (op == 1) ((Runnable) ticket).run();
			else if (service != null) {
				if (op == 0) service.endForkJoinSubmission(ticket, failure);
				else service.endForkJoinReset(ticket, failure);
			}
		}
		public void error(Throwable failure) { errors.accept("forkjoin-bootstrap-incomplete:" + failure); }
	}
}
