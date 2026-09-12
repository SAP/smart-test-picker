// SPDX-FileCopyrightText: 2024-2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker;

import java.lang.reflect.InvocationTargetException;

/** Accesses the single JaCoCo agent active in the test JVM. */
final class JacocoExecutionDataSource
{
	private static final String UNAVAILABLE = "Active JaCoCo runtime is unavailable for STP mapping";

	private final Object agent;

	JacocoExecutionDataSource(Object agent)
	{
		this.agent = agent;
	}

	static JacocoExecutionDataSource active()
	{
		try
		{
			Class<?> rt = loadRuntimeClass();
			return new JacocoExecutionDataSource(rt.getMethod("getAgent").invoke(null));
		}
		catch (ReflectiveOperationException | LinkageError failure)
		{
			throw new IllegalStateException(UNAVAILABLE, unwrap(failure));
		}
	}

	void startSession(String sessionId)
	{
		invoke("setSessionId", new Class<?>[] { String.class }, sessionId);
		invoke("reset", new Class<?>[0]);
	}

	byte[] snapshotAndReset()
	{
		return (byte[]) invoke("getExecutionData", new Class<?>[] { boolean.class }, true);
	}

	private Object invoke(String method, Class<?>[] parameterTypes, Object... arguments)
	{
		try
		{
			return agent.getClass().getMethod(method, parameterTypes).invoke(agent, arguments);
		}
		catch (ReflectiveOperationException | LinkageError failure)
		{
			throw new IllegalStateException("Active JaCoCo runtime is unusable for STP mapping", unwrap(failure));
		}
	}

	private static Class<?> loadRuntimeClass() throws ClassNotFoundException
	{
		ClassLoader[] loaders = { Thread.currentThread().getContextClassLoader(),
				ClassLoader.getSystemClassLoader(), JacocoExecutionDataSource.class.getClassLoader() };
		for (ClassLoader loader : loaders)
		{
			if (loader == null) continue;
			try
			{
				return Class.forName("org.jacoco.agent.rt.RT", true, loader);
			}
			catch (ClassNotFoundException ignored) { }
		}
		throw new ClassNotFoundException("org.jacoco.agent.rt.RT");
	}

	private static Throwable unwrap(Throwable failure)
	{
		return failure instanceof InvocationTargetException && failure.getCause() != null
				? failure.getCause() : failure;
	}
}
