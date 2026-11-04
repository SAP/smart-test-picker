package observation;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.Set;
import org.objectweb.asm.*;

/** Test-only observation of JaCoCo runtime methods, never project bytecode or probe state. */
public final class ProbeObserver {
    private static Path output;
    public static void premain(String file, Instrumentation instrumentation) throws Exception {
        output = Path.of(file); Files.createDirectories(output.getParent());
        Files.writeString(output, "OBSERVER_READY\n");
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (name == null || !name.startsWith("org/jacoco/agent/rt/internal_")) return null;
                if (!name.endsWith("/Agent") && !name.endsWith("/core/runtime/RuntimeData")) return null;
                try {
                    var reader = new ClassReader(bytes);
                    var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override public MethodVisitor visitMethod(int access, String method, String descriptor,
                                String signature, String[] exceptions) {
                            MethodVisitor delegate = super.visitMethod(access, method, descriptor, signature, exceptions);
                            if (!Set.of("reset", "dump", "getExecutionData", "collect").contains(method)) return delegate;
                            // The other RuntimeData overload serves ordinary probe lookup, not a snapshot/reset.
                            if (method.equals("getExecutionData") && !descriptor.equals("(Z)[B")) return delegate;
                            append("WATCH " + name + "#" + method + descriptor);
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override public void visitCode() {
                                    super.visitCode();
                                    visitLdcInsn(name + "#" + method + descriptor);
                                    if (descriptor.startsWith("(Z)")) visitVarInsn(Opcodes.ILOAD, 1);
                                    else if (method.equals("collect")) visitVarInsn(Opcodes.ILOAD, 3);
                                    else visitInsn(Opcodes.ICONST_0);
                                    visitMethodInsn(Opcodes.INVOKESTATIC, "observation/ProbeObserver", "called", "(Ljava/lang/String;Z)V", false);
                                }
                            };
                        }
                    }, 0);
                    return writer.toByteArray();
                } catch (Throwable failure) {
                    append("OBSERVER_ERROR " + failure); throw new AssertionError(failure);
                }
            }
        });
    }
    public static void called(String method, boolean reset) {
        StringBuilder message = new StringBuilder("CALL ").append(method).append(" reset=").append(reset);
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) message.append("\n  at ").append(frame);
        append(message.toString());
    }
    private static synchronized void append(String message) {
        try { Files.writeString(output, message + "\n", StandardOpenOption.APPEND); }
        catch (Exception failure) { throw new AssertionError("Cannot record JaCoCo runtime observation", failure); }
    }
}
