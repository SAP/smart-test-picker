package observation;

import java.lang.instrument.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.security.ProtectionDomain;
import java.util.*;
import org.objectweb.asm.*;

/** Disposable read-only observer of existing STP routing; no application or framework transformation. */
public final class ContextObserver {
    private static Path output;
    private static long sequence;
    private static volatile String interval = "none";
    private static volatile Object service;
    public static void premain(String file, Instrumentation instrumentation) throws Exception {
        output=Path.of(file); Files.createDirectories(output.getParent());
        Files.writeString(output,"sequence\tevent\tthread\tinterval\tactiveTest\tcapturedContainer\tcurrentContainer\tcapturedActive\tdetail\n");
        emit("READY",null,"observer loaded; bounded to 20000 rows");
        instrumentation.addTransformer(new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes) {
                if(!Set.of("com/sap/oss/smarttestpicker/runtime/RuntimeContextService",
                    "com/sap/oss/smarttestpicker/runtime/RuntimeEventAggregator").contains(name==null?"":name)) return null;
                try {
                    var reader=new ClassReader(bytes);var writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);
                    reader.accept(new ClassVisitor(Opcodes.ASM9,writer) {
                        public MethodVisitor visitMethod(int access,String method,String desc,String signature,String[] exceptions) {
                            var delegate=super.visitMethod(access,method,desc,signature,exceptions);
                            boolean context=name.endsWith("RuntimeContextService");
                            String event=context ? switch(method) {
                                case "record" -> "HIT";
                                case "beginTest" -> "BEGIN";
                                case "endTest" -> "END";
                                case "wrap", "wrapSupplier", "wrapFunction" -> "CAPTURE";
                                default -> null;
                            } : method.equals("recordSetup") ? "SETUP" : method.equals("recordSetupDiagnostic") ? "DIAGNOSTIC" : null;
                            if(event==null)return delegate;
                            emit("WATCH",null,name+"#"+method+desc+" loader="+loader);
                            return new MethodVisitor(Opcodes.ASM9,delegate) {
                                public void visitCode() {
                                    super.visitCode();visitLdcInsn(event);visitVarInsn(Opcodes.ALOAD,0);
                                    visitVarInsn(Opcodes.ALOAD,1);
                                    if(event.equals("SETUP")) visitVarInsn(Opcodes.ALOAD,3);else visitInsn(Opcodes.ACONST_NULL);
                                    visitMethodInsn(Opcodes.INVOKESTATIC,"observation/ContextObserver","observe",
                                        "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false);
                                }
                            };
                        }
                    },0);
                    return writer.toByteArray();
                } catch(Throwable failure) { emit("ERROR",null,failure.toString());throw new AssertionError(failure); }
            }
        });
    }
    public static void observe(String event,Object owner,Object value,Object extra) {
        try {
            if(!event.equals("SETUP") && !event.equals("DIAGNOSTIC"))service=owner;
            String detail;
            switch(event) {
                case "BEGIN" -> {interval=identity(value);detail=interval;}
                case "END" -> detail=identity(value);
                case "HIT" -> detail=method(value);
                case "SETUP" -> detail=value+"|"+method(extra);
                case "DIAGNOSTIC" -> detail=call(value,"kind")+"|"+call(value,"severity")+"|"+call(value,"method")+"|"+call(value,"detail");
                case "CAPTURE" -> {
                    detail=value.getClass().getName()+"@"+Integer.toHexString(System.identityHashCode(value));
                    detail+=" stack="+Arrays.toString(Thread.currentThread().getStackTrace());
                }
                default -> throw new IllegalArgumentException(event);
            }
            emit(event,service,detail);
            if(event.equals("END"))interval="none";
        } catch(Throwable failure) {emit("ERROR",null,failure.toString());throw new AssertionError(failure);}
    }
    private static Object call(Object value,String method) throws Exception {
        Method m=value.getClass().getDeclaredMethod(method);m.setAccessible(true);return m.invoke(value);
    }
    private static String identity(Object value) throws Exception {
        return value==null?"none":call(value,"testClass")+"#"+call(value,"testMethod");
    }
    private static String method(Object event) throws Exception {
        return String.valueOf(call(call(event,"method"),"canonicalKey"));
    }
    private static Object local(Object owner,String name) throws Exception {
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return ((ThreadLocal<?>)f.get(owner)).get();
    }
    private static String container(Object value) throws Exception {
        return value==null?"none":String.valueOf(call(value,"binaryName"));
    }
    private static synchronized void emit(String event,Object owner,String detail) {
        try {
            if(++sequence>20000)throw new IllegalStateException("Observer bound exceeded");
            String active="none",captured="none",current="none",capturedActive="none";
            if(owner!=null) {
                Optional<?> test=(Optional<?>)owner.getClass().getMethod("currentTest").invoke(owner);
                active=identity(test.orElse(null));captured=container(local(owner,"capturedContainer"));
                Deque<?> stack=(Deque<?>)local(owner,"currentContainers");current=container(stack.peek());
                Object capturedValue=local(owner,"capturedContainer");
                if(capturedValue!=null) {
                    Field field=owner.getClass().getDeclaredField("activeContainers");field.setAccessible(true);
                    capturedActive=String.valueOf(((Set<?>)field.get(owner)).contains(call(capturedValue,"uniqueId")));
                }
            }
            Files.writeString(output,sequence+"\t"+event+"\t"+Thread.currentThread().getName()+"\t"+interval+"\t"+active+"\t"+captured+"\t"+current+"\t"+capturedActive+"\t"+detail.replace('\t',' ').replace('\n',' ')+"\n",StandardOpenOption.APPEND);
        } catch(Exception failure) {throw new AssertionError("Observation failed",failure);}
    }
}
