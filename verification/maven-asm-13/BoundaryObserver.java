package observation;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import org.objectweb.asm.*;

/** Observes STP callbacks and holds one callback at a declared test-only boundary; never invokes reset. */
public final class BoundaryObserver {
    public static void premain(String unused, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String name, Class<?> type, ProtectionDomain domain, byte[] bytes) {
                boolean data = "com/sap/oss/smarttestpicker/JacocoExecutionDataSource".equals(name);
                boolean listener = "com/sap/oss/smarttestpicker/JacocoPerTestListener".equals(name);
                boolean runtime = "com/sap/oss/smarttestpicker/runtime/RuntimeContextService".equals(name);
                if (!data && !listener && !runtime) return null;
                var reader = new ClassReader(bytes); var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
                        var delegate = super.visitMethod(access, method, desc, signature, exceptions);
                        boolean watch = data && (method.equals("startSession") || method.equals("snapshotAndReset"))
                            || listener && (method.equals("executionStarted") || method.equals("executionFinished"))
                            || runtime && (method.equals("beginTest") || method.equals("endTest") || method.equals("record"));
                        if (!watch) return delegate;
                        String event = (data ? "JACOCO_DATA_" : listener ? "JACOCO_LISTENER_" : "ASM_") + method;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            void hook(String phase) {
                                if (runtime && method.equals("record")) {
                                    if (phase.equals("ENTER")) {
                                        visitVarInsn(Opcodes.ALOAD, 0); visitVarInsn(Opcodes.ALOAD, 1);
                                        visitMethodInsn(Opcodes.INVOKESTATIC, "observation/BoundaryObserver", "hit", "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                    }
                                    return;
                                }
                                visitLdcInsn(event + "_" + phase);
                                if (method.equals("snapshotAndReset")) visitInsn(Opcodes.ACONST_NULL); else visitVarInsn(Opcodes.ALOAD, 1);
                                visitMethodInsn(Opcodes.INVOKESTATIC, "observation/BoundaryObserver", "event", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
                            }
                            public void visitCode() { super.visitCode(); hook("ENTER"); }
                            public void visitInsn(int opcode) {
                                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) hook("EXIT");
                                super.visitInsn(opcode);
                            }
                        };
                    }
                }, 0);
                return writer.toByteArray();
            }
        });
    }
    public static void hit(Object owner, Object event) {
        try {
            var field = owner.getClass().getDeclaredField("capturedContainer"); field.setAccessible(true);
            Object captured = ((ThreadLocal<?>)field.get(owner)).get();
            Object active = owner.getClass().getMethod("currentTest").invoke(owner);
            boolean open = false;
            if (captured != null) {
                var id = captured.getClass().getDeclaredMethod("uniqueId"); id.setAccessible(true);
                var containers = owner.getClass().getDeclaredField("activeContainers"); containers.setAccessible(true);
                open = ((java.util.Set<?>)containers.get(owner)).contains(id.invoke(captured));
            }
            Gates.mark("ASM_HIT", event + " active=" + active + " captured=" + captured + " open=" + open);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    public static void event(String event, Object identity) {
        String value = identity == null ? "" : identity.toString();
        Gates.mark(event, value);
        if (Gates.parallel() && value.contains("bFollowing") &&
            (event.equals("JACOCO_DATA_startSession_ENTER") || event.equals("ASM_beginTest_ENTER"))) {
            // JaCoCo's own startSession reset follows this gate. No synthetic reset is introduced.
            Gates.await("LATE_DONE");
        }
        if (value.contains("bFollowing") &&
            (event.equals("JACOCO_LISTENER_executionFinished_EXIT") || event.equals("ASM_endTest_EXIT"))) {
            // In ASM mode the disabled JaCoCo listener still gets callbacks. Do not release early from it.
            boolean asm = "ASM".equalsIgnoreCase(System.getProperty("smartTestPicker.coverageCollector"));
            if (asm == event.startsWith("ASM_")) Gates.signal("FOLLOWING_FINISHED");
        }
    }
}
