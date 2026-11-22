// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0

import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** Extracts the documented configuration surface using the JDK parser, without resolving dependencies. */
public final class SourceSurface {
    private static final Set<String> PUBLISHED = Set.of("smart-test-picker", "smart-test-picker-common",
            "smart-test-picker-core", "smart-test-picker-maven", "smart-test-picker-cli", "stp-agent");
    private static final List<Map<String, String>> rows = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        List<Path> paths = new ArrayList<>();
        for (String module : PUBLISHED) {
            try (var files = Files.walk(root.resolve(module + "/src/main/java"))) {
                files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(paths::add);
            }
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, d -> {
                if (d.getKind() == Diagnostic.Kind.ERROR) throw new IllegalStateException(d.toString());
            }, List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(paths));
            DocTrees docs = DocTrees.instance(task);
            for (CompilationUnitTree unit : task.parse()) {
                String file = root.relativize(Path.of(unit.getSourceFile().toUri())).toString();
                for (Tree declaration : unit.getTypeDecls()) {
                    if (!(declaration instanceof ClassTree type)) continue;
                    TreePath typePath = TreePath.getPath(unit, type);
                    if (type.getModifiers().getFlags().contains(Modifier.PUBLIC)) {
                        add("type", file + "#" + type.getSimpleName(), file, "", "", doc(docs, typePath), "");
                    }
                    Map<String, String> mojo = annotation(type.getModifiers(), "Mojo");
                    if (mojo != null) {
                        String goal = mojo.get("name");
                        add("maven-goal", "maven:" + goal, file, "not applicable", "", doc(docs, typePath), "");
                        for (Tree member : type.getMembers()) {
                            if (!(member instanceof VariableTree field)) continue;
                            Map<String, String> parameter = annotation(field.getModifiers(), "Parameter");
                            if (parameter == null) continue;
                            add("maven-parameter", "maven:" + goal + ":" + field.getName(), file,
                                    parameter.getOrDefault("defaultValue", ""), parameter.getOrDefault("property", ""),
                                    doc(docs, new TreePath(typePath, field)), parameter.getOrDefault("readonly", "false"));
                        }
                    }
                    String extension = switch (type.getSimpleName().toString()) {
                        case "SmartTestPickerExtension" -> "";
                        case "RemoteStoreExtension" -> "remoteStore.";
                        case "RemoteStoreCredentials" -> "remoteStore.credentials.";
                        default -> null;
                    };
                    if (extension != null) {
                        for (Tree member : type.getMembers()) {
                            if (!(member instanceof MethodTree method) || !method.getName().toString().startsWith("get")
                                    || !method.getParameters().isEmpty()) continue;
                            String name = method.getName().toString().substring(3);
                            add("gradle-property", "gradle:" + extension + Character.toLowerCase(name.charAt(0)) + name.substring(1),
                                    file, "", "", doc(docs, new TreePath(typePath, method)), "");
                        }
                    }
                    Map<String, String> command = annotation(type.getModifiers(), "Command");
                    if (command != null) {
                        String name = command.get("name");
                        add("cli-command", "cli:" + name, file, "not applicable", "", doc(docs, typePath), "");
                        new TreePathScanner<Void, Void>() {
                            @Override public Void visitVariable(VariableTree field, Void unused) {
                                Map<String, String> option = annotation(field.getModifiers(), "Option");
                                if (option != null) {
                                    for (String alias : option.get("names").split("\n")) {
                                        add("cli-option", "cli:" + name + ":" + alias, file,
                                                option.getOrDefault("defaultValue", field.getInitializer() == null
                                                        ? (field.getType().toString().equals("boolean") ? "false" : "")
                                                        : value(field.getInitializer())), "", option.getOrDefault("description", ""), "");
                                    }
                                }
                                return super.visitVariable(field, unused);
                            }
                        }.scan(typePath, null);
                        if ("true".equals(command.get("mixinStandardHelpOptions"))) {
                            for (String alias : List.of("-h", "--help", "-V", "--version")) {
                                add("cli-option", "cli:" + name + ":" + alias, file, "false", "", "Picocli standard help/version option.", "");
                            }
                        }
                    }
                }
            }
        }
        rows.sort(Comparator.comparing(r -> r.get("id")));
        System.out.println("[");
        for (int i = 0; i < rows.size(); i++) {
            if (i != 0) System.out.println(",");
            var entries = rows.get(i).entrySet().stream().map(e -> quote(e.getKey()) + ":" + quote(e.getValue())).toList();
            System.out.print("{" + String.join(",", entries) + "}");
        }
        System.out.println("\n]");
    }

    private static String doc(DocTrees trees, TreePath path) {
        var comment = trees.getDocCommentTree(path);
        return comment == null ? "" : comment.toString();
    }

    private static Map<String, String> annotation(ModifiersTree modifiers, String name) {
        for (AnnotationTree annotation : modifiers.getAnnotations()) {
            String type = annotation.getAnnotationType().toString();
            if (!type.equals(name) && !type.endsWith("." + name)) continue;
            Map<String, String> values = new LinkedHashMap<>();
            for (ExpressionTree argument : annotation.getArguments()) {
                if (argument instanceof AssignmentTree assignment) {
                    values.put(assignment.getVariable().toString(), value(assignment.getExpression()));
                }
            }
            return values;
        }
        return null;
    }

    private static String value(ExpressionTree expression) {
        if (expression instanceof LiteralTree literal) return String.valueOf(literal.getValue());
        if (expression instanceof NewArrayTree array) return String.join("\n", array.getInitializers().stream().map(SourceSurface::value).toList());
        return expression.toString();
    }

    private static void add(String kind, String id, String file, String defaultValue, String property, String doc, String readonly) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("kind", kind); row.put("id", id); row.put("file", file); row.put("default", defaultValue);
        row.put("property", property); row.put("doc", doc); row.put("readonly", readonly);
        rows.add(row);
    }

    private static String quote(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> { if (c < 32) result.append(String.format("\\u%04x", (int) c)); else result.append(c); }
            }
        }
        return result.append('"').toString();
    }
}
