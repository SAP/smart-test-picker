// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package com.sap.oss.smarttestpicker.karate;

import com.intuit.karate.core.Feature;
import com.intuit.karate.core.FeatureSection;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Stable semantic identity. Source line and execution indexes are deliberately absent. */
final class KarateTestIdentity {
    private KarateTestIdentity() { }

    static String create(String featurePath, String scenarioName, Map<String, Object> example) {
        String id = sanitize(scenarioName) + "-" + hash(normalizePath(featurePath));
        if (example != null) id += "-" + hash(canonical(example));
        if (id.length() > 256) throw new IllegalArgumentException("STP TestID exceeds 256 characters; shorten the Scenario name (no silent truncation)");
        return id;
    }

    static String sanitize(String name) {
        if (name == null) throw new IllegalArgumentException("STP Scenario name is missing");
        String value = Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        if (value.isEmpty()) throw new IllegalArgumentException("STP Scenario name is empty after sanitization");
        return value;
    }

    static String normalizePath(String value) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("STP feature resource path is missing or invalid");
        String path = value.replace('\\', '/');
        boolean classpath = path.startsWith("classpath:");
        if (classpath) path = path.substring("classpath:".length());
        if (path.startsWith("/") || path.contains(":"))
            throw new IllegalArgumentException("STP TestID requires a relative Karate resource path, not an absolute filesystem path: " + value);
        ArrayDeque<String> parts = new ArrayDeque<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (parts.isEmpty()) throw new IllegalArgumentException("STP feature path escapes its resource root: " + value);
                parts.removeLast();
            } else parts.addLast(part);
        }
        if (parts.isEmpty()) throw new IllegalArgumentException("STP feature resource path is empty");
        return (classpath ? "classpath:" : "") + String.join("/", parts);
    }

    // Capture definition names before Karate evaluates dynamic display names. Validate all
    // sections, including unselected ones, so adding tags cannot hide ambiguous identities.
    static Map<FeatureSection, String> definitionNames(Feature feature) {
        Map<FeatureSection, String> names = new IdentityHashMap<>();
        Map<String, String> unique = new TreeMap<>();
        for (FeatureSection section : feature.getSections()) {
            String name = section.isOutline() ? section.getScenarioOutline().getName() : section.getScenario().getName();
            String slug = sanitize(name);
            if (unique.putIfAbsent(slug, name) != null)
                throw new IllegalArgumentException("Ambiguous STP Scenario names after sanitization in "
                        + feature.getResource().getPrefixedPath() + ": " + slug);
            names.put(section, name);
        }
        return Collections.unmodifiableMap(names);
    }

    static String canonical(Object value) {
        return canonical(value, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static String canonical(Object value, Set<Object> visiting) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean bool) return bool.toString();
        if (value instanceof Number number) {
            try { return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString(); }
            catch (NumberFormatException failure) { throw new IllegalArgumentException("STP example data must contain finite JSON numbers"); }
        }
        if (!visiting.add(value)) throw new IllegalArgumentException("Cyclic STP example data is unsupported");
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> sorted = new TreeMap<>();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("STP example object keys must be strings");
                    sorted.put(key, entry.getValue());
                }
                var members = new java.util.ArrayList<String>();
                sorted.forEach((key, item) -> members.add(quote(key) + ":" + canonical(item, visiting)));
                return "{" + String.join(",", members) + "}";
            }
            if (value instanceof List<?> list) {
                var items = new java.util.ArrayList<String>();
                for (Object item : list) items.add(canonical(item, visiting));
                return "[" + String.join(",", items) + "]";
            }
            throw new IllegalArgumentException("STP example data must be JSON values, not " + value.getClass().getName());
        } finally { visiting.remove(value); }
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20 || Character.isSurrogate(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
