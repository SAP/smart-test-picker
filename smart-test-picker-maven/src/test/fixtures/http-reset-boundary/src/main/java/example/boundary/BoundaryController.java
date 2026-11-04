package example.boundary;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import observation.Gates;

@RestController
public class BoundaryController {
    @GetMapping("/late")
    public void late(HttpServletResponse response) throws Exception {
        response.setContentLength(2);
        response.getOutputStream().write(new byte[] {'O', 'K'});
        response.flushBuffer();
        Gates.mark("SERVER_FLUSHED", "response has fixed length 2");
        Gates.await("RELEASE_LATE");
        Gates.completed(PostResponseOnly.distinctiveAfterResponse());
    }
}
