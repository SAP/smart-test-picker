package example.shared;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only proof of context object reuse and actual HTTP transport, plus a direct-call positive control. */
final class ContextReuse {
    private static ServletWebServerApplicationContext firstContext;
    private static String firstClass;

    static synchronized void verify(String testClass, ServletWebServerApplicationContext context,
            PingController controller) throws Exception {
        if (firstContext == null) { firstContext = context; firstClass = testClass; }
        assertSame(firstContext, context, "Spring must reuse the exact context object across classes");
        int port = context.getWebServer().getPort();
        String directThread = controller.ping();
        assertEquals(Thread.currentThread().getName(), directThread);
        var connection = (HttpURLConnection) URI.create("http://localhost:" + port + "/ping").toURL().openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        assertEquals(200, connection.getResponseCode());
        String serverThread;
        try (var body = connection.getInputStream()) { serverThread = new String(body.readAllBytes(), StandardCharsets.UTF_8); }
        finally { connection.disconnect(); }
        assertTrue(serverThread.startsWith("http-nio-"), serverThread);
        assertNotEquals(directThread, serverThread);
        String row = testClass + "\t" + context.getId() + "\t" + System.identityHashCode(context)
            + "\t" + context.getStartupDate() + "\t" + port + "\t" + serverThread
            + "\t" + firstClass + "\ttrue\t" + directThread + "\n";
        Path output = Path.of(System.getProperty("fixture.evidence"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        System.out.print("CONTEXT_REUSE\t" + row);
    }
}
