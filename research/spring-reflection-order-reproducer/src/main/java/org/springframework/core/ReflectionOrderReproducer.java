package org.springframework.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.util.ReflectionUtils;

public final class ReflectionOrderReproducer {
    public interface GenericParameter<T> { T getFor(Class<T> type); }

    public static final class StringGenericParameter implements GenericParameter<String> {
        @Override public String getFor(Class<String> type) { return "class"; }
        public String getFor(Integer value) { return "integer"; }
    }

    public static void main(String[] args) throws Exception {
        Method[] raw = StringGenericParameter.class.getDeclaredMethods();
        Method bridge = Arrays.stream(raw).filter(method -> method.isBridge() && method.getName().equals("getFor"))
                .findFirst().orElseThrow(() -> new AssertionError("fixture compiler did not emit the expected bridge method"));

        List<Method> candidates = new ArrayList<>();
        ReflectionUtils.doWithMethods(StringGenericParameter.class, candidates::add,
                method -> !method.isBridge() && method.getName().equals(bridge.getName())
                        && method.getParameterCount() == bridge.getParameterCount());

        List<String> decisions = new ArrayList<>();
        for (Method candidate : candidates) {
            boolean match = BridgeMethodResolver.isBridgeMethodFor(bridge, candidate, bridge.getDeclaringClass());
            decisions.add(key(candidate) + "=" + match);
            if (match) break;
        }
        Method resolved = BridgeMethodResolver.findBridgedMethod(bridge);

        System.out.println("raw=" + Arrays.stream(raw).map(ReflectionOrderReproducer::key).toList());
        System.out.println("candidates=" + candidates.stream().map(ReflectionOrderReproducer::key).toList());
        System.out.println("actualSpringPredicatePath=" + decisions);
        System.out.println("resolved=" + key(resolved));
    }

    private static String key(Method method) {
        return method.getName() + Arrays.toString(method.getParameterTypes()) + "->" + method.getReturnType().getTypeName()
                + (method.isBridge() ? "[bridge]" : "");
    }
}
