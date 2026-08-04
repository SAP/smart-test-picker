// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.core.env.Environment;
import org.springframework.data.repository.Repository;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class AcceptanceAuditRecorder implements DisposableBean {
	private static final String INSTALLATIONS =
			"com.sap.oss.smarttestpicker.springdata.internalAdvisorInstallationRegistry";
	private static final String METRICS = "com.sap.oss.smarttestpicker.springdata.internalRepositoryMetrics";
	private final Environment environment;
	private final ConfigurableListableBeanFactory beanFactory;
	private final Object injectedVetRepository;
	private final List<BeanAudit> beans = new ArrayList<>();
	private String installationResults = "[]";
	private String metrics = "{}";

	AcceptanceAuditRecorder(Environment environment, ConfigurableListableBeanFactory beanFactory,
			Object injectedVetRepository) {
		this.environment = environment;
		this.beanFactory = beanFactory;
		this.injectedVetRepository = injectedVetRepository;
	}

	synchronized void capture() {
		beans.clear();
		for (String name : beanFactory.getSingletonNames()) {
			Object first = beanFactory.getBean(name);
			if (!(first instanceof Repository)) continue;
			Object second = beanFactory.getBean(name);
			if (!(first instanceof Advised advised)) continue;
			List<String> advisors = Arrays.stream(advised.getAdvisors())
					.map(value -> value.getClass().getName() + "|" + value.getAdvice().getClass().getName()).toList();
			List<Integer> cachePositions = new ArrayList<>();
			for (int index = 0; index < advised.getAdvisors().length; index++) {
				if (advised.getAdvisors()[index].getAdvice() instanceof CacheInterceptor) cachePositions.add(index);
			}
			long stpCount = Arrays.stream(advised.getAdvisors()).filter(value ->
					value.getClass().getName().equals("com.sap.oss.smarttestpicker.springdata.StpCallerAdvisor")).count();
			beans.add(new BeanAudit(name, first == second, name.equals("vetRepository") && first == injectedVetRepository,
					AopUtils.isJdkDynamicProxy(first) ? "JDK" : AopUtils.isCglibProxy(first) ? "CLASS_BASED" : "OTHER",
					Arrays.stream(advised.getProxiedInterfaces()).map(Class::getName).sorted().toList(),
					System.identityHashCode(advised.getTargetSource()), advisors, stpCount, cachePositions, 1));
		}
		beans.sort(Comparator.comparing(BeanAudit::beanName));
		installationResults = reflect(beanFactory.getBean(INSTALLATIONS), "results");
	}

	private static String reflect(Object target, String methodName) {
		try {
			Method method = target.getClass().getDeclaredMethod(methodName);
			method.setAccessible(true);
			return String.valueOf(method.invoke(target));
		}
		catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Cannot read STP acceptance state", failure);
		}
	}

	@Override
	public synchronized void destroy() throws Exception {
		String output = environment.getProperty("stp.acceptance.audit.output");
		if (output == null || output.isBlank()) return;
		metrics = reflect(beanFactory.getBean(METRICS), "snapshot");
		Path path = Path.of(output);
		Files.createDirectories(path.getParent());
		StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": \"spring-data-acceptance-audit-1\",\n")
				.append("  \"installationResults\": ").append(quote(installationResults)).append(",\n")
				.append("  \"metrics\": ").append(quote(metrics)).append(",\n  \"beans\": [");
		for (int index = 0; index < beans.size(); index++) {
			if (index > 0) json.append(',');
			BeanAudit bean = beans.get(index);
			json.append("\n    {\"beanName\":").append(quote(bean.beanName()))
					.append(",\"sameLookupIdentity\":").append(bean.sameLookupIdentity())
					.append(",\"injectedVetIsExposedBean\":").append(bean.injectedVetIsExposedBean())
					.append(",\"proxyKind\":").append(quote(bean.proxyKind()))
					.append(",\"interfaces\":").append(strings(bean.interfaces()))
					.append(",\"targetSourceIdentityToken\":").append(bean.targetSourceIdentityToken())
					.append(",\"advisors\":").append(strings(bean.advisors()))
					.append(",\"stpAdvisorCount\":").append(bean.stpAdvisorCount())
					.append(",\"cachePositions\":").append(bean.cachePositions())
					.append(",\"proxyDepth\":").append(bean.proxyDepth()).append('}');
		}
		json.append("\n  ]\n}\n");
		Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
	}

	private static String strings(List<String> values) {
		return "[" + values.stream().map(AcceptanceAuditRecorder::quote)
				.reduce((left, right) -> left + "," + right).orElse("") + "]";
	}

	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private record BeanAudit(String beanName, boolean sameLookupIdentity, boolean injectedVetIsExposedBean,
			String proxyKind, List<String> interfaces, int targetSourceIdentityToken, List<String> advisors,
			long stpAdvisorCount, List<Integer> cachePositions, int proxyDepth) {
	}
}
