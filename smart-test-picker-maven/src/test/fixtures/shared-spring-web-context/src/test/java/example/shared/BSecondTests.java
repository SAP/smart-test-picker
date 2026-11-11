package example.shared;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

@SpringBootTest(classes = SharedApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BSecondTests {
    @Autowired ServletWebServerApplicationContext context;
    @Autowired PingController controller;

    @Test void realRequest() throws Exception {
        ContextReuse.verify(getClass().getName(), context, controller);
    }
}
