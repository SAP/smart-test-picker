// SPDX-FileCopyrightText: 2026 SAP SE or an SAP affiliate company and Smart Test Picker contributors
// SPDX-License-Identifier: Apache-2.0
package task20.jacoco;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.IFrame;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.runtime.OfflineInstrumentationAccessGenerator;
import org.jacoco.core.tools.ExecFileLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Printer;

public final class Task20JacocoInspector {
    private static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
    private static String hex(long value) { return String.format("0x%016x", value); }
    private static String sha256(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static String ints(List<Integer> values) { return values.toString(); }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("exec class-file internal-name method descriptor instrumented-output");
        File exec = new File(args[0]);
        byte[] bytes = Files.readAllBytes(Path.of(args[1]));
        String className = args[2], methodName = args[3], descriptor = args[4];
        ExecFileLoader loader = new ExecFileLoader(); loader.load(exec);
        long classId = CRC64.classId(bytes);
        ExecutionData execution = loader.getExecutionDataStore().get(classId);
        CoverageBuilder coverage = new CoverageBuilder(); new Analyzer(loader.getExecutionDataStore(), coverage).analyzeClass(bytes, args[1]);
        IClassCoverage selectedClass = coverage.getClasses().stream().filter(c -> c.getName().equals(className)).findFirst().orElse(null);
        IMethodCoverage selectedMethod = selectedClass == null ? null : selectedClass.getMethods().stream()
                .filter(m -> m.getName().equals(methodName) && m.getDesc().equals(descriptor)).findFirst().orElse(null);

        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        MethodNode bytecode = node.methods.stream().filter(m -> m.name.equals(methodName) && m.desc.equals(descriptor)).findFirst().orElseThrow();
        List<String> instructions = new ArrayList<>();
        for (AbstractInsnNode instruction : bytecode.instructions) {
            if (instruction.getOpcode() >= 0) instructions.add(Printer.OPCODES[instruction.getOpcode()]);
        }
        List<Integer> probes = new ArrayList<>();
        ClassProbesVisitor visitor = new ClassProbesVisitor() {
            @Override public MethodProbesVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (!name.equals(methodName) || !desc.equals(descriptor)) return null;
                return new MethodProbesVisitor() {
                    private void add(int id) { if (!probes.contains(id)) probes.add(id); }
                    @Override public void visitProbe(int id) { add(id); }
                    @Override public void visitInsnWithProbe(int opcode, int id) { add(id); }
                    @Override public void visitJumpInsnWithProbe(int opcode, Label label, int id, IFrame frame) { add(id); }
                    @Override public void visitTableSwitchInsnWithProbes(int min, int max, Label dflt, Label[] labels, IFrame frame) {
                        // Probe ids on switch labels are visited through visitProbe.
                    }
                    @Override public void visitLookupSwitchInsnWithProbes(Label dflt, int[] keys, Label[] labels, IFrame frame) { }
                };
            }
            @Override public void visitTotalProbeCount(int count) { }
        };
        new ClassReader(bytes).accept(new ClassProbesAdapter(visitor, false), ClassReader.EXPAND_FRAMES);
        List<Integer> hit = new ArrayList<>();
        if (execution != null) for (int id : probes) if (id < execution.getProbes().length && execution.getProbes()[id]) hit.add(id);
        byte[] instrumented = new Instrumenter(new OfflineInstrumentationAccessGenerator()).instrument(bytes, className);
        Files.createDirectories(Path.of(args[5]).getParent()); Files.write(Path.of(args[5]), instrumented);

        int instructionCovered = selectedMethod == null ? 0 : selectedMethod.getInstructionCounter().getCoveredCount();
        int instructionMissed = selectedMethod == null ? 0 : selectedMethod.getInstructionCounter().getMissedCount();
        int branchCovered = selectedMethod == null ? 0 : selectedMethod.getBranchCounter().getCoveredCount();
        int branchMissed = selectedMethod == null ? 0 : selectedMethod.getBranchCounter().getMissedCount();
        boolean covered = instructionCovered > 0;
        System.out.println("{");
        System.out.println("  \"className\": " + q(className) + ",");
        System.out.println("  \"methodName\": " + q(methodName) + ",");
        System.out.println("  \"descriptor\": " + q(descriptor) + ",");
        System.out.println("  \"originalSha256\": " + q(sha256(bytes)) + ",");
        System.out.println("  \"instrumentedSha256\": " + q(sha256(instrumented)) + ",");
        System.out.println("  \"analyzerClassId\": " + q(hex(classId)) + ",");
        System.out.println("  \"executionClassId\": " + (execution == null ? "null" : q(hex(execution.getId()))) + ",");
        System.out.println("  \"executionClassName\": " + (execution == null ? "null" : q(execution.getName())) + ",");
        System.out.println("  \"executionProbeCount\": " + (execution == null ? 0 : execution.getProbes().length) + ",");
        System.out.println("  \"classPresentInAnalyzer\": " + (selectedClass != null) + ",");
        System.out.println("  \"methodPresentInAnalyzer\": " + (selectedMethod != null) + ",");
        System.out.println("  \"directAnalyzerCovered\": " + covered + ",");
        System.out.println("  \"instructionCovered\": " + instructionCovered + ",");
        System.out.println("  \"instructionMissed\": " + instructionMissed + ",");
        System.out.println("  \"branchCovered\": " + branchCovered + ",");
        System.out.println("  \"branchMissed\": " + branchMissed + ",");
        System.out.println("  \"access\": " + bytecode.access + ",");
        System.out.println("  \"synthetic\": " + ((bytecode.access & Opcodes.ACC_SYNTHETIC) != 0) + ",");
        System.out.println("  \"bridge\": " + ((bytecode.access & Opcodes.ACC_BRIDGE) != 0) + ",");
        System.out.println("  \"abstract\": " + ((bytecode.access & Opcodes.ACC_ABSTRACT) != 0) + ",");
        System.out.println("  \"native\": " + ((bytecode.access & Opcodes.ACC_NATIVE) != 0) + ",");
        System.out.println("  \"constructor\": " + methodName.equals("<init>") + ",");
        System.out.println("  \"enumClass\": " + ((node.access & Opcodes.ACC_ENUM) != 0) + ",");
        System.out.println("  \"instructionCount\": " + instructions.size() + ",");
        System.out.println("  \"instructions\": " + q(String.join(" ", instructions)) + ",");
        System.out.println("  \"relevantProbeIds\": " + ints(probes) + ",");
        System.out.println("  \"hitProbeIds\": " + ints(hit));
        System.out.println("}");
    }
}
