// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package example.remote;

import com.sap.oss.smarttestpicker.remote.RemoteTestContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/** Real HTTP fixture for application-created Thread and virtual-thread callbacks. */
public final class DirectThreadFixtureMain {
	private static final Map<String, Thread> THREADS = new ConcurrentHashMap<>();
	private static final Map<String, String> RESULTS = new ConcurrentHashMap<>();
	private static final Map<String, String> NAMES_AND_GROUPS = new ConcurrentHashMap<>();
	private static final Map<String, String> FAILURE_CONTEXTS = new ConcurrentHashMap<>();
	private static final CyclicBarrier SHARED_THREADS = new CyclicBarrier(2);
	private static final Runnable SHARED_RUNNABLE = () -> {
		if (Thread.currentThread().getName().endsWith("A")) ThreadApplication.sharedA();
		else ThreadApplication.sharedB();
		try { SHARED_THREADS.await(5, TimeUnit.SECONDS); }
		catch (Exception failure) { throw new IllegalStateException("shared threads did not overlap", failure); }
	};
	private static Class<?> virtualCaller;
	private static boolean virtualSupported;
	private DirectThreadFixtureMain() { }

	public static void main(String[] args) throws Exception {
		try {
			Thread.class.getMethod("startVirtualThread", Runnable.class);
			Thread.class.getMethod("ofVirtual");
			virtualSupported = true;
			virtualCaller = new GeneratedClassLoader().define(virtualThreadCaller());
		} catch (NoSuchMethodException unsupported) { virtualSupported = false; }
		Server server = new Server(0);
		ServletContextHandler context = new ServletContextHandler();
		context.setContextPath("/");
		context.addServlet(new ServletHolder(new Handler()), "/*");
		server.setHandler(context);
		server.start();
		System.out.println("READY:" + server.getURI().getPort());
		System.out.println("VIRTUAL_SUPPORTED:" + virtualSupported);
		System.out.flush();
		server.join();
	}

	public static final class Handler extends HttpServlet {
		@Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
			String path = request.getPathInfo();
			String body;
			try {
				if (path.startsWith("/create/")) { create(path.substring(8)); body = "created"; }
				else if (path.startsWith("/start/")) { body = start(path.substring(7)); }
				else if (path.equals("/create-shared/A")) { THREADS.put("shared-A", new Thread(SHARED_RUNNABLE, "shared-A")); body = "created"; }
				else if (path.equals("/create-shared/B")) { THREADS.put("shared-B", new Thread(SHARED_RUNNABLE, "shared-B")); body = "created"; }
				else if (path.equals("/start-shared")) { THREADS.get("shared-A").start(); THREADS.get("shared-B").start();
					THREADS.get("shared-A").join(6000); THREADS.get("shared-B").join(6000); body = "done"; }
				else if (path.startsWith("/virtual/")) { body = virtual(path.substring(9), false); }
				else if (path.startsWith("/builder/")) { body = virtual(path.substring(9), true); }
				else if (path.startsWith("/result/")) { body = RESULTS.getOrDefault(path.substring(8), "missing"); }
				else if (path.startsWith("/metadata/")) { body = NAMES_AND_GROUPS.getOrDefault(path.substring(10), "missing"); }
				else if (path.startsWith("/failure-context/")) { body = FAILURE_CONTEXTS.getOrDefault(path.substring(17), "missing"); }
				else { response.sendError(404); return; }
			} catch (Exception failure) { throw new IOException("direct thread fixture failed", failure); }
			response.setStatus(200); response.getWriter().write(body);
		}
		private static void create(String spec) {
			String[] parts = spec.split("-", 2); String kind = parts[0]; String key = parts[1];
			Runnable task = switch (kind) {
				case "plain", "named", "group", "groupnamed" -> () -> ThreadApplication.run(key);
				case "failure" -> () -> ThreadApplication.fail(key);
				case "nocontext" -> ThreadApplication::noContext;
				default -> throw new IllegalArgumentException("unknown thread kind " + kind);
			};
			ThreadGroup group = new ThreadGroup("group-" + key);
			Thread thread = switch (kind) {
				case "plain", "failure", "nocontext" -> new Thread(task);
				case "named" -> new Thread(task, "supplied-name-" + key);
				case "group" -> new Thread(group, task);
				case "groupnamed" -> new Thread(group, task, "supplied-name-" + key);
				default -> throw new IllegalArgumentException("unknown thread kind " + kind);
			};
			if (kind.equals("failure")) thread.setUncaughtExceptionHandler((failed, thrown) ->
					FAILURE_CONTEXTS.put(key, String.valueOf(RemoteTestContext.currentId())));
			THREADS.put(key, thread);
		}
		private static String start(String key) throws InterruptedException {
			Thread thread = THREADS.get(key);
			thread.start(); thread.join(6000);
			if (thread.isAlive()) throw new IllegalStateException("thread did not finish: " + key);
			return "started:" + thread.getName();
		}
		private static String virtual(String key, boolean builder) throws Exception {
			if (!virtualSupported) return "unsupported";
			Runnable task = () -> ThreadApplication.virtual(key);
			Method method = virtualCaller.getMethod(builder ? "builderStart" : "startVirtual", Runnable.class);
			Thread thread = (Thread) method.invoke(null, task);
			thread.join(6000);
			if (thread.isAlive()) throw new IllegalStateException("virtual thread did not finish");
			return Boolean.toString(thread.isVirtual());
		}
	}

	public static final class ThreadApplication {
		public static void run(String key) { ThreadRepository.run(key); NAMES_AND_GROUPS.put(key,
				Thread.currentThread().getName() + "|" + Thread.currentThread().getThreadGroup().getName()); }
		public static void fail(String key) { ThreadRepository.failure(key); throw new IllegalStateException("expected thread failure"); }
		public static void noContext() { ThreadRepository.noContext(); }
		public static void sharedA() { ThreadRepository.sharedA(); }
		public static void sharedB() { ThreadRepository.sharedB(); }
		public static void virtual(String key) { ThreadRepository.virtual(key); }
	}
	public static final class ThreadRepository {
		public static void run(String key) { RESULTS.put(key, "ran"); }
		public static void failure(String key) { RESULTS.put(key, "failed"); }
		public static void noContext() { }
		public static void sharedA() { }
		public static void sharedB() { }
		public static void virtual(String key) { }
	}

	private static final class GeneratedClassLoader extends ClassLoader {
		GeneratedClassLoader() { super(DirectThreadFixtureMain.class.getClassLoader()); }
		Class<?> define(byte[] bytecode) { return defineClass("example.remote.VirtualThreadCallSiteFixture", bytecode, 0, bytecode.length); }
	}
	private static byte[] virtualThreadCaller() {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/remote/VirtualThreadCallSiteFixture", null, "java/lang/Object", null);
		MethodVisitor virtual = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "startVirtual",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;", null, null);
		virtual.visitCode(); virtual.visitVarInsn(Opcodes.ALOAD, 0);
		virtual.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "startVirtualThread", "(Ljava/lang/Runnable;)Ljava/lang/Thread;", false);
		virtual.visitInsn(Opcodes.ARETURN); virtual.visitMaxs(1, 1); virtual.visitEnd();
		MethodVisitor builder = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "builderStart",
				"(Ljava/lang/Runnable;)Ljava/lang/Thread;", null, null);
		builder.visitCode();
		builder.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "ofVirtual", "()Ljava/lang/Thread$Builder$OfVirtual;", false);
		builder.visitVarInsn(Opcodes.ALOAD, 0);
		builder.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Thread$Builder", "start", "(Ljava/lang/Runnable;)Ljava/lang/Thread;", true);
		builder.visitInsn(Opcodes.ARETURN); builder.visitMaxs(2, 1); builder.visitEnd();
		writer.visitEnd(); return writer.toByteArray();
	}
}
