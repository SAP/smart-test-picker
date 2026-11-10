package probe;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.List;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InMemoryTest {
    @Test
    void recordsDynamicAndControl() throws Exception {
        assertEquals(17, generatedprobe.ControlValue.value());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        var source = new SimpleJavaFileObject(URI.create("string:///generatedprobe/DynamicValue.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) {
                return "package generatedprobe; public class DynamicValue { public static int value() { return 42; } }";
            }
        };
        var bytes = new ByteArrayOutputStream();
        try (var manager = new ForwardingJavaFileManager<>(compiler.getStandardFileManager(null, null, null)) {
            @Override public JavaFileObject getJavaFileForOutput(Location location, String name,
                    JavaFileObject.Kind kind, FileObject sibling) {
                assertEquals("generatedprobe.DynamicValue", name);
                return new SimpleJavaFileObject(URI.create("memory:///generatedprobe/DynamicValue.class"), kind) {
                    @Override public OutputStream openOutputStream() { return bytes; }
                };
            }
        }) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-proc:none", "--release", "17"),
                    null, List.of(source)).call());
        }
        class MemoryLoader extends ClassLoader {
            MemoryLoader() { super(InMemoryTest.class.getClassLoader()); }
            Class<?> define(byte[] code) { return defineClass("generatedprobe.DynamicValue", code, 0, code.length); }
        }
        var dynamic = new MemoryLoader().define(bytes.toByteArray());
        assertNull(dynamic.getProtectionDomain().getCodeSource().getLocation());
        assertNull(dynamic.getResource("/generatedprobe/DynamicValue.class"));
        assertEquals(42, dynamic.getMethod("value").invoke(null));
        System.out.println("DynamicValue: compiled to byte array; resource absent; code-source location=null; value=42");
        System.out.println("ControlValue: code-source=" + generatedprobe.ControlValue.class.getProtectionDomain().getCodeSource().getLocation() + "; value=17");
    }
}
