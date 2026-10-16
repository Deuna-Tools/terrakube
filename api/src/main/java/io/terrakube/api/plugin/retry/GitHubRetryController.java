package io.terrakube.api.plugin.retry;

import com.yahoo.elide.core.security.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/retry/v1/actions")
@RequiredArgsConstructor
public class GitHubRetryController {
    private final PlanRetryAuthorization authorization;
    private final GitHubPlanRetry github;

    @GetMapping("/{runId}/controller")
    public Map<String, Long> controller(@PathVariable long runId, Authentication authentication) {
        if (!authorization.isController(new User((Principal) authentication))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return Map.of("jobId", github.controllerJob(runId));
    }
}
