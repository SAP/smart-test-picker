// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.springdata;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.interceptor.CacheInterceptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class ProxyStructuralFingerprint {
	private final Object bean;
	private final RepositoryProxyKind proxyKind;
	private final List<String> exposedInterfaces;
	private final Object targetSource;
	private final List<Advisor> advisors;
	private final List<Integer> cachePositions;
	private final int proxyLayerCount;

	private ProxyStructuralFingerprint(Object bean, RepositoryProxyKind proxyKind, List<String> exposedInterfaces,
			Object targetSource, List<Advisor> advisors, List<Integer> cachePositions, int proxyLayerCount) {
		this.bean = bean;
		this.proxyKind = proxyKind;
		this.exposedInterfaces = List.copyOf(exposedInterfaces);
		this.targetSource = targetSource;
		this.advisors = List.copyOf(advisors);
		this.cachePositions = List.copyOf(cachePositions);
		this.proxyLayerCount = proxyLayerCount;
	}

	static ProxyStructuralFingerprint capture(Object bean, Advised advised) {
		RepositoryProxyKind kind = AopUtils.isJdkDynamicProxy(bean) ? RepositoryProxyKind.JDK
				: AopUtils.isCglibProxy(bean) ? RepositoryProxyKind.CLASS_BASED : RepositoryProxyKind.NOT_AOP_PROXY;
		List<String> interfaces = Arrays.stream(advised.getProxiedInterfaces()).map(Class::getName).sorted().toList();
		List<Advisor> advisors = List.of(advised.getAdvisors());
		return new ProxyStructuralFingerprint(bean, kind, interfaces, advised.getTargetSource(), advisors,
				cachePositions(advisors), 1);
	}

	static List<Integer> cachePositions(List<Advisor> advisors) {
		List<Integer> positions = new ArrayList<>();
		for (int index = 0; index < advisors.size(); index++) {
			if (advisors.get(index).getAdvice() instanceof CacheInterceptor) positions.add(index);
		}
		return List.copyOf(positions);
	}

	Object bean() { return bean; }
	RepositoryProxyKind proxyKind() { return proxyKind; }
	List<String> exposedInterfaces() { return exposedInterfaces; }
	Object targetSource() { return targetSource; }
	List<Advisor> advisors() { return advisors; }
	List<Integer> cachePositions() { return cachePositions; }
	int proxyLayerCount() { return proxyLayerCount; }
}
