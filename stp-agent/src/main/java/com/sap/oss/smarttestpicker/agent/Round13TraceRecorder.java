// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.agent;

import com.sap.oss.smarttestpicker.runtime.RuntimeContextRegistry;
import com.sap.oss.smarttestpicker.runtime.RuntimeContextService;
import com.sap.oss.smarttestpicker.runtime.RuntimeHooks;
import com.sap.oss.smarttestpicker.runtime.model.TestIdentity;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic, bounded and opt-in ROUND 13 receiver/cache operation recorder. */
final class Round13TraceRecorder implements RuntimeHooks.Round13Sink {
	private static final String MAP = "org.springframework.util.ConcurrentReferenceHashMap";
	private static final String[][] OWNERS = {
		{"org.springframework.core.ResolvableType", "cache", "ResolvableType.cache"},
		{"org.springframework.core.SerializableTypeWrapper", "cache", "SerializableTypeWrapper.cache"},
		{"org.springframework.core.BridgeMethodResolver", "cache", "BridgeMethodResolver.cache"},
		{"org.springframework.core.GenericTypeResolver", "typeVariableCache", "GenericTypeResolver.typeVariableCache"},
		{"org.springframework.util.ReflectionUtils", "declaredMethodsCache", "ReflectionUtils.declaredMethodsCache"},
		{"org.springframework.util.ReflectionUtils", "declaredFieldsCache", "ReflectionUtils.declaredFieldsCache"},
		{"org.springframework.util.ClassUtils", "interfaceMethodCache", "ClassUtils.interfaceMethodCache"}
	};
	private final Path output;
	private final int limit;
	private final long origin = System.nanoTime();
	private final AtomicLong sequence = new AtomicLong();
	private final List<String> events = new ArrayList<>();
	private final IdentityHashMap<Object,String> owners = new IdentityHashMap<>();
	private final IdentityHashMap<Object,Object> segmentParents = new IdentityHashMap<>();
	private final IdentityHashMap<Object,Object> referenceParents = new IdentityHashMap<>();
	private final ThreadLocal<ArrayDeque<Before>> calls = ThreadLocal.withInitial(ArrayDeque::new);
	private long dropped;

	private Round13TraceRecorder(Path output, int limit) { this.output = output; this.limit = limit; }
	static Round13TraceRecorder fromSystemProperties() {
		String value = System.getProperty("stp.round13.trace.output");
		return new Round13TraceRecorder(value == null || value.isBlank() ? null : Path.of(value), Integer.getInteger("stp.round13.trace.limit", 50000));
	}
	boolean enabled() { return output != null; }

	@Override public void testEvent(String event) {
		if (!enabled()) return;
		if ("TEST_START".equals(event)) discoverOwners();
		record(event, null, null, null, null, null);
	}

	@Override public void enter(String method, Object receiver, Object argument) {
		if (!enabled()) return;
		resolve(receiver);
		State before = state(receiver);
		calls.get().push(new Before(method, receiver, argument, before));
		record("METHOD_ENTER", method, receiver, argument, null, before);
	}

	@Override public void exit(String method, Object receiver, Object result) {
		if (!enabled()) return;
		Before before = pop(method, receiver);
		if (method.contains("#createReference") && result != null) referenceParents.put(result, receiver);
		resolve(receiver); resolve(result);
		State after = state(receiver);
		String outcome = outcome(method, before == null ? null : before.state, after, result);
		record("METHOD_EXIT", method, receiver, before == null ? null : before.argument, result, after, outcome);
	}

	private Before pop(String method, Object receiver) {
		ArrayDeque<Before> stack = calls.get();
		if (stack.isEmpty()) return null;
		Before value = stack.pop();
		return value.method.equals(method) && value.receiver == receiver ? value : null;
	}

	private synchronized void discoverOwners() {
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		for (String[] candidate : OWNERS) try {
			Class<?> type = Class.forName(candidate[0], true, loader);
			Field field = type.getDeclaredField(candidate[1]); field.setAccessible(true);
			Object value = field.get(null); if (value != null) { owners.put(value, candidate[2]); indexMap(value); }
		} catch (Throwable ignored) { }
	}

	private synchronized void resolve(Object value) {
		if (value == null) return;
		if (value.getClass().getName().equals(MAP)) indexMap(value);
		Object parent = field(value, "this$0");
		if (parent != null && parent.getClass().getName().equals(MAP)) { segmentParents.put(value, parent); indexMap(parent); }
		if (value.getClass().getName().contains("EntryReference")) findReferenceParent(value);
	}

	private void indexMap(Object map) {
		Object segments = field(map, "segments"); if (segments == null || !segments.getClass().isArray()) return;
		for (int i = 0; i < Array.getLength(segments); i++) { Object segment = Array.get(segments, i); if (segment != null) { segmentParents.put(segment, map); indexReferences(segment); } }
	}

	private void indexReferences(Object segment) {
		Object table = field(segment, "references"); if (table == null || !table.getClass().isArray()) return;
		for (int i=0; i<Array.getLength(table); i++) {
			Object ref = Array.get(table, i); Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			while (ref != null && seen.add(ref)) { referenceParents.put(ref, segment); ref = field(ref, "nextReference"); }
		}
	}

	private void findReferenceParent(Object reference) {
		if (referenceParents.containsKey(reference)) return;
		for (Object segment : List.copyOf(segmentParents.keySet())) { indexReferences(segment); if (referenceParents.containsKey(reference)) return; }
	}

	private State state(Object receiver) {
		if (receiver == null) return State.EMPTY;
		Object segment = receiver.getClass().getName().endsWith("$Segment") ? receiver : referenceParents.get(receiver);
		if (segment == null && receiver.getClass().getName().equals(MAP)) return new State(number(receiver, "size"), null, arrayLength(field(receiver,"segments")), null, null, null);
		if (segment == null && receiver.getClass().getName().equals("org.springframework.core.ResolvableType"))
			return new State(null, null, null, null, null, field(receiver, "interfaces") != null);
		if (segment == null) return State.EMPTY;
		return new State(intField(segment,"count"), intField(segment,"resizeThreshold"), arrayLength(field(segment,"references")),
				presentReferences(segment), referentPresent(receiver), null);
	}

	private int presentReferences(Object segment) {
		Object table = field(segment,"references"); if (table == null) return 0; int count=0;
		for (int i=0;i<Array.getLength(table);i++) for(Object ref=Array.get(table,i);ref!=null;ref=field(ref,"nextReference")) count++;
		return count;
	}
	private Boolean referentPresent(Object receiver) {
		if (!receiver.getClass().getName().contains("EntryReference")) return null;
		// Reference.get() is observationally safe here: no enqueue/clear/retention mutation is performed.
		return receiver instanceof java.lang.ref.Reference<?> ref ? ref.get() != null : null;
	}

	private String outcome(String method, State before, State after, Object result) {
		if (method.startsWith(MAP) && method.contains("#getReference(")) return result == null ? "GET_MISS" : "GET_HIT";
		if (method.contains("#get(") && method.startsWith(MAP) && result != null) return "GET_HIT";
		if (method.contains("#put") && before != null && after != null && before.count != null && after.count != null)
			return after.count > before.count ? "PUT_NEW" : "PUT_REPLACE";
		if (method.contains("$Segment#restructure(")) {
			boolean resize = before != null && after != null && !Objects.equals(before.tableSize, after.tableSize);
			boolean purge = before != null && after != null && before.referenceCount != null && after.referenceCount != null && after.referenceCount < before.referenceCount;
			if (resize) return "RESTRUCTURE_RESIZE"; if (purge) return "RESTRUCTURE_PURGE"; return "RESTRUCTURE_NOOP";
		}
		return null;
	}

	private void record(String kind, String method, Object receiver, Object argument, Object result, State state) { record(kind,method,receiver,argument,result,state,null); }
	private synchronized void record(String kind, String method, Object receiver, Object argument, Object result, State state, String outcome) {
		long seq = sequence.incrementAndGet(); if (events.size() >= limit) { dropped++; return; }
		RuntimeContextService.DiagnosticContext context = RuntimeContextRegistry.current().map(RuntimeContextService::diagnosticContext).orElse(null);
		TestIdentity test = context == null ? null : context.testIdentity(); Thread thread = Thread.currentThread();
		Object segment = receiver == null ? null : (receiver.getClass().getName().endsWith("$Segment") ? receiver : referenceParents.get(receiver));
		Object map = receiver == null ? null : (receiver.getClass().getName().equals(MAP) ? receiver : segmentParents.get(segment));
		String owner = owners.get(map); Object key = isKeyMethod(method) ? argument : null;
		String json = "{\"sequence\":"+seq+",\"nanoTimestamp\":"+System.nanoTime()+",\"nanoOffset\":"+(System.nanoTime()-origin)
				+",\"event\":"+q(kind)+",\"testIdentity\":"+q(test == null ? null : test.testClass()+"#"+test.testMethod())
				+",\"logicalTestIdentity\":"+q(test == null ? null : test.platformUniqueId())+",\"threadId\":"+thread.getId()+",\"thread\":"+q(thread.getName())
				+",\"method\":"+q(method)+",\"receiverIdentity\":"+identity(receiver)+",\"receiverClass\":"+q(className(receiver))
				+",\"resolvedCacheOwner\":"+q(owner)+",\"operation\":"+q(operation(method))+",\"outcome\":"+q(outcome)
				+",\"keyIdentity\":"+identity(key)+",\"keyClass\":"+q(className(key))+",\"keyHashCode\":"+safeHash(key)
				+",\"segmentIdentity\":"+identity(segment)+",\"referenceIdentity\":"+(receiver != null && receiver.getClass().getName().contains("EntryReference") ? identity(receiver) : "null")
				+",\"resultIdentity\":"+identity(result)+",\"count\":"+num(state.count)+",\"resizeThreshold\":"+num(state.threshold)
				+",\"tableSize\":"+num(state.tableSize)+",\"referenceCount\":"+num(state.referenceCount)+",\"referentPresent\":"+(state.referentPresent==null?"null":state.referentPresent)
				+",\"interfacesInitialized\":"+(state.interfacesInitialized==null?"null":state.interfacesInitialized)+"}";
		events.add(json);
	}

	void write() throws IOException {
		if (!enabled()) return; List<String> snapshot; Map<String,Object> ownerSnapshot = new TreeMap<>();
		synchronized(this) { snapshot=List.copyOf(events); owners.forEach((key,value)->ownerSnapshot.put(value,key)); }
		StringBuilder out=new StringBuilder("{\n  \"schemaVersion\": \"round13-runtime-trace-1\",\n  \"identityScope\": \"same JVM execution only\",\n  \"droppedEvents\": ").append(dropped).append(",\n  \"cacheOwners\": [");
		int i=0; for(var entry:ownerSnapshot.entrySet()) out.append(i++==0?"\n    ":",\n    ").append("{\"owner\":").append(q(entry.getKey())).append(",\"identity\":").append(identity(entry.getValue())).append("}");
		if(i>0)out.append('\n').append("  "); out.append("],\n  \"events\": ["); for(i=0;i<snapshot.size();i++)out.append(i==0?"\n    ":",\n    ").append(snapshot.get(i));
		if(!snapshot.isEmpty())out.append('\n').append("  "); out.append("]\n}\n"); Files.writeString(output,out,StandardCharsets.UTF_8);
	}

	private static boolean isKeyMethod(String method) { return method != null && (method.contains("#get(") || method.contains("#getReference(") || method.contains("#put(" ) || method.contains("#putIfAbsent(")); }
	private static String operation(String method) { if(method==null)return null; int hash=method.indexOf('#'), open=method.indexOf('(',hash); return hash<0?method:method.substring(hash+1,open<0?method.length():open); }
	private static Object field(Object target,String name) { if(target==null)return null; try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch(Throwable ignored){return null;} }
	private static Integer intField(Object target,String name) { Object value=field(target,name); if(value instanceof java.util.concurrent.atomic.AtomicInteger a)return a.get(); return value instanceof Number n?n.intValue():null; }
	private static Integer number(Object target,String method) { try{return ((Number)target.getClass().getMethod(method).invoke(target)).intValue();}catch(Throwable ignored){return null;} }
	private static Integer arrayLength(Object value){return value!=null&&value.getClass().isArray()?Array.getLength(value):null;}
	private static String className(Object value){return value==null?null:value.getClass().getName();}
	private static String identity(Object value){return value==null?"null":Integer.toString(System.identityHashCode(value));}
	private static String safeHash(Object value){if(value==null)return"null";try{return Integer.toString(value.hashCode());}catch(Throwable ignored){return"null";}}
	private static String num(Integer value){return value==null?"null":value.toString();}
	private static String q(String value){return value==null?"null":"\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
	private record Before(String method,Object receiver,Object argument,State state){}
	private record State(Integer count,Integer threshold,Integer tableSize,Integer referenceCount,Boolean referentPresent,Boolean interfacesInitialized){static final State EMPTY=new State(null,null,null,null,null,null);}
}
