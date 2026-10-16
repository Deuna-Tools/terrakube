package io.terrakube.api.plugin.retry;

import com.yahoo.elide.core.security.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/retry/v1/jobs/{jobId}")
@RequiredArgsConstructor
public class PlanRetryController {
    private final PlanRetryService retries;
    public record Request(int expectedAttempt, long githubRunId) {}

    @GetMapping
    public Map<String, Object> describe(@PathVariable int jobId, Authentication authentication) {
        return retries.describe(jobId, new User((Principal) authentication));
    }

    @PostMapping("/plan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable int jobId, @RequestBody Request request, Authentication authentication) {
        retries.request(jobId, request.expectedAttempt(), new User((Principal) authentication));
    }

    @PostMapping("/activate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void activate(@PathVariable int jobId, @RequestBody Request request, Authentication authentication) {
        retries.activate(jobId, request.expectedAttempt(), new User((Principal) authentication), request.githubRunId());
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<Map<String, String>> rejected(org.springframework.web.server.ResponseStatusException error) {
        return org.springframework.http.ResponseEntity.status(error.getStatusCode())
                .body(Map.of("message", error.getReason() == null ? "Not authorized" : error.getReason()));
    }
}
