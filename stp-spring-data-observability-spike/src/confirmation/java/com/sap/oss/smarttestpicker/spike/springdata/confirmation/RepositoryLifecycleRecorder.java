// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.spike.springdata.confirmation;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.cache.interceptor.CacheInterceptor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

final class RepositoryLifecycleRecorder implements DisposableBean {
	private static final List<String> TARGETS = List.of("ownerRepository", "vetRepository");
	private final Environment environment;
	private final ConfigurableListableBeanFactory beanFactory;
	private final String contextIdentity;
	private final AtomicLong sequence = new AtomicLong();
	private final List<Snapshot> snapshots = new ArrayList<>();
	private final Map<String, Object> previousBeans = new LinkedHashMap<>();
	private final Map<String, Advisor> inserted = new LinkedHashMap<>();
	private final IdentityHashMap<Object, Integer> identityTokens = new IdentityHashMap<>();
	private int nextIdentityToken = 1;

	RepositoryLifecycleRecorder(Environment environment, ConfigurableListableBeanFactory beanFactory,
			String contextIdentity) {
		this.environment = environment;
		this.beanFactory = beanFactory;
		this.contextIdentity = contextIdentity;
	}

	BeanPostProcessor factoryObserver() {
		return new FactoryObserver();
	}

	BeanPostProcessor productObserver() {
		return new ProductObserver();
	}

	BeanPostProcessor earlyProductObserver() {
		return new EarlyProductObserver();
	}

	synchronized void lifecyclePoint(String phase) {
		captureAll(phase + "_BEFORE_INSERTION");
		String mode = environment.getProperty("stp.confirmation.mode", "");
		if ((phase.equals("SMART_INITIALIZING_SINGLETON") && mode.equals("lifecycle-smart-singletons"))
				|| (phase.equals("CONTEXT_REFRESHED_EVENT") && mode.equals("lifecycle-context-refreshed"))) {
			insertAll(phase + "_INSERTION");
		}
		captureAll(phase + "_AFTER_INSERTION");
	}

	synchronized void captureAll(String phase) {
		for (String name : TARGETS) {
			if (beanFactory.containsSingleton(name)) capture(phase, name, beanFactory.getBean(name), null);
		}
	}

	private synchronized void insertAll(String phase) {
		for (String name : TARGETS) {
			if (!beanFactory.containsSingleton(name)) continue;
			Object bean = beanFactory.getBean(name);
			if (inserted.containsKey(name) || !(bean instanceof Advised advised)) continue;
			Advisor advisor = diagnosticAdvisor(name);
			advised.addAdvisor(0, advisor);
			inserted.put(name, advisor);
			capture(phase, name, bean, null);
		}
	}

	private synchronized void insert(String phase, String name, Object bean) {
		if (inserted.containsKey(name) || !(bean instanceof Advised advised)) return;
		Advisor advisor = diagnosticAdvisor(name);
		advised.addAdvisor(0, advisor);
		inserted.put(name, advisor);
		capture(phase, name, bean, null);
	}

	private Advisor diagnosticAdvisor(String beanName) {
		return new DefaultPointcutAdvisor(new LifecycleDiagnosticInterceptor(beanName));
	}

	private final class LifecycleDiagnosticInterceptor implements LifecycleDiagnosticAdvice {
		private final String beanName;

		private LifecycleDiagnosticInterceptor(String beanName) {
			this.beanName = beanName;
		}

		@Override
		public Object invoke(org.aopalliance.intercept.MethodInvocation invocation) throws Throwable {
			capture("IMMEDIATELY_BEFORE_FIRST_REPOSITORY_INVOCATION", beanName,
					beanFactory.getBean(beanName), null);
			try {
				Object result = invocation.proceed();
				capture("IMMEDIATELY_AFTER_REPOSITORY_INVOCATION", beanName,
						beanFactory.getBean(beanName), null);
				return result;
			}
			catch (Throwable failure) {
				capture("IMMEDIATELY_AFTER_FAILED_REPOSITORY_INVOCATION", beanName,
						beanFactory.getBean(beanName), null);
				throw failure;
			}
		}
	}

	private synchronized void capture(String phase, String beanName, Object bean, Advised supplied) {
		Advised advised = supplied != null ? supplied : bean instanceof Advised value ? value : null;
		Object previous = previousBeans.put(beanName, bean);
		String proxyKind = AopUtils.isJdkDynamicProxy(bean) ? "JDK"
				: AopUtils.isCglibProxy(bean) ? "CLASS_BASED" : "NOT_AOP_PROXY";
		List<String> interfaces = advised == null ? List.of() : Arrays.stream(advised.getProxiedInterfaces())
				.map(Class::getName).sorted().toList();
		int targetToken = advised == null ? 0 : token(advised.getTargetSource());
		int depth = proxyDepth(bean);
		Advisor[] advisors = advised == null ? new Advisor[0] : advised.getAdvisors();
		if (advisors.length == 0) {
			snapshots.add(new Snapshot(sequence.incrementAndGet(), phase, beanName, previous == null || previous == bean,
					proxyKind, depth, interfaces, -1, null, 0, targetToken, List.of(), List.of(), List.of(),
					contextIdentity(), Thread.currentThread().getName()));
			return;
		}
		List<Integer> cache = positions(advisors, CacheInterceptor.class);
		List<Integer> transactions = positions(advisors, TransactionInterceptor.class);
		List<Integer> diagnostic = positions(advisors, LifecycleDiagnosticAdvice.class);
		for (int index = 0; index < advisors.length; index++) {
			Advisor advisor = advisors[index];
			snapshots.add(new Snapshot(sequence.incrementAndGet(), phase, beanName, previous == null || previous == bean,
					proxyKind, depth, interfaces, index,
					advisor.getClass().getName() + "|" + advisor.getAdvice().getClass().getName(), token(advisor),
					targetToken, cache, transactions, diagnostic, contextIdentity(), Thread.currentThread().getName()));
		}
	}

	private static List<Integer> positions(Advisor[] advisors, Class<?> adviceType) {
		List<Integer> result = new ArrayList<>();
		for (int index = 0; index < advisors.length; index++) {
			if (adviceType.isInstance(advisors[index].getAdvice())) result.add(index);
		}
		return List.copyOf(result);
	}

	private int proxyDepth(Object bean) {
		int depth = 0;
		Object current = bean;
		IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
		while (current instanceof Advised advised && seen.put(current, Boolean.TRUE) == null) {
			depth++;
			try {
				Object target = advised.getTargetSource().getTarget();
				if (target == null || target == current) break;
				current = target;
			}
			catch (Exception ignored) {
				break;
			}
		}
		return depth;
	}

	private int token(Object value) {
		if (value == null) return 0;
		return identityTokens.computeIfAbsent(value, ignored -> nextIdentityToken++);
	}

	private String contextIdentity() {
		return contextIdentity;
	}

	@Override
	public synchronized void destroy() throws IOException {
		captureAll("CONTEXT_SHUTDOWN_DESTROY");
		String output = environment.getProperty("stp.lifecycle.output");
		if (output == null || output.isBlank()) return;
		Path path = Path.of(output);
		Files.createDirectories(path.getParent());
		Files.writeString(path, json(), StandardCharsets.UTF_8);
	}

	private String json() {
		StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": \"spring-repository-lifecycle-1\",\n")
				.append("  \"mode\": ").append(quote(environment.getProperty("stp.confirmation.mode"))).append(",\n")
				.append("  \"snapshots\": [");
		for (int index = 0; index < snapshots.size(); index++) {
			if (index > 0) json.append(',');
			Snapshot value = snapshots.get(index);
			json.append("\n    {")
					.append("\"sequence\":").append(value.sequence())
					.append(",\"phase\":").append(quote(value.phase()))
					.append(",\"canonicalBeanName\":").append(quote(value.canonicalBeanName()))
					.append(",\"sameBeanIdentityAsPrevious\":").append(value.sameBeanIdentityAsPrevious())
					.append(",\"proxyKind\":").append(quote(value.proxyKind()))
					.append(",\"proxyLayerCount\":").append(value.proxyLayerCount())
					.append(",\"exposedInterfaces\":").append(strings(value.exposedInterfaces()))
					.append(",\"advisorIndex\":").append(value.advisorIndex())
					.append(",\"advisorType\":").append(quote(value.advisorType()))
					.append(",\"advisorObjectIdentityToken\":").append(value.advisorObjectIdentityToken())
					.append(",\"targetSourceIdentityToken\":").append(value.targetSourceIdentityToken())
					.append(",\"cacheAdvisorPositions\":").append(value.cacheAdvisorPositions())
					.append(",\"transactionAdvisorPositions\":").append(value.transactionAdvisorPositions())
					.append(",\"stpAdvisorPositions\":").append(value.stpAdvisorPositions())
					.append(",\"applicationContextIdentity\":").append(quote(value.applicationContextIdentity()))
					.append(",\"threadName\":").append(quote(value.threadName())).append('}');
		}
		return json.append("\n  ]\n}\n").toString();
	}

	private static String strings(List<String> values) {
		return "[" + values.stream().map(RepositoryLifecycleRecorder::quote)
				.reduce((left, right) -> left + "," + right).orElse("") + "]";
	}

	private static String quote(String value) {
		return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	private final class FactoryObserver implements BeanPostProcessor, Ordered {
		@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
			if (TARGETS.contains(beanName) && bean instanceof RepositoryFactoryBeanSupport<?, ?, ?> factory) {
				capture("REPOSITORY_FACTORY_CUSTOMIZATION", beanName, bean, null);
				factory.addRepositoryFactoryCustomizer(repositoryFactory -> {
					repositoryFactory.addRepositoryProxyPostProcessor((proxyFactory, repositoryInformation) -> {
						capture("REPOSITORY_PROXY_POST_PROCESSOR", beanName, proxyFactory, proxyFactory);
					});
					repositoryFactory.addInvocationListener(invocation -> capture(
							"SPRING_DATA_INVOCATION_LISTENER_AFTER", beanName, beanFactory.getBean(beanName), null));
				});
			}
			return bean;
		}
	}

	private final class ProductObserver implements BeanPostProcessor, Ordered {
		@Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			if (!TARGETS.contains(beanName) || !(bean instanceof Repository)) return bean;
			capture("REPOSITORY_PRODUCT_POST_PROCESS_AFTER_INITIALIZATION", beanName, bean, null);
			if (environment.getProperty("stp.confirmation.mode", "").equals("lifecycle-late-bpp")) {
				insert("LATE_BEAN_POST_PROCESSOR_INSERTION", beanName, bean);
			}
			capture("REPOSITORY_PRODUCT_POST_PROCESS_AFTER_INITIALIZATION_RETURN", beanName, bean, null);
			return bean;
		}
	}

	private final class EarlyProductObserver implements BeanPostProcessor, Ordered {
		@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
			if (TARGETS.contains(beanName) && bean instanceof Repository) {
				capture("REPOSITORY_PRODUCT_EARLY_BPP_AFTER_INITIALIZATION", beanName, bean, null);
			}
			return bean;
		}
	}

	private interface LifecycleDiagnosticAdvice extends MethodInterceptor {
	}

	private record Snapshot(long sequence, String phase, String canonicalBeanName,
			boolean sameBeanIdentityAsPrevious, String proxyKind, int proxyLayerCount, List<String> exposedInterfaces,
			int advisorIndex, String advisorType, int advisorObjectIdentityToken, int targetSourceIdentityToken,
			List<Integer> cacheAdvisorPositions, List<Integer> transactionAdvisorPositions,
			List<Integer> stpAdvisorPositions, String applicationContextIdentity, String threadName) {
	}
}
