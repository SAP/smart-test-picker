package example.boundary;

import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import observation.Gates;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = BoundaryApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.MethodName.class)
@Execution(ExecutionMode.CONCURRENT)
class BoundaryTests {
    @LocalServerPort int port;

    @Test void aRequest() throws Exception {
        Gates.mark("REQUEST_TEST_BODY", "HTTP only; no direct production call");
        var connection = (HttpURLConnection) URI.create("http://localhost:" + port + "/late").toURL().openConnection();
        connection.setReadTimeout(20000); connection.setConnectTimeout(20000);
        assertEquals(200, connection.getResponseCode());
        try (var body = connection.getInputStream()) {
            assertEquals("OK", new String(body.readAllBytes(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
        Gates.signal("RESPONSE_READ");
        if (Gates.parallel()) {
            Gates.signal("RELEASE_LATE");
            Gates.await("FOLLOWING_FINISHED");
        }
        Gates.mark("REQUEST_TEST_RETURN", "");
    }

    @Test void bFollowing() {
        Gates.mark("FOLLOWING_TEST_BODY", "no production calls");
        Gates.await("RESPONSE_READ");
        if (!Gates.parallel()) Gates.signal("RELEASE_LATE");
        Gates.await("LATE_DONE");
        Gates.mark("FOLLOWING_TEST_RETURN", "");
    }
}
