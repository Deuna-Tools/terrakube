package io.terrakube.api.plugin.context;

import com.fasterxml.jackson.core.JacksonException;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.server.ResponseStatusException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import io.terrakube.api.plugin.streaming.StreamingService;

import java.io.IOException;
import java.security.Principal;
import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;

@Slf4j
@RestController
@RequestMapping("/context/v1")
@AllArgsConstructor
public class ContextController {
    private static final String PENDING_BODY = "{\"structuredOutputStatus\":{\"state\":\"PENDING\"}}";

    private final ContextSanitizer contextSanitizer;

    private final StreamingService streamingService;

    private final ContextReadService contextReadService;

    private final ContextProperties contextProperties;

    private final ContextWriteService contextWriteService;

    private final ObjectMapper objectMapper;

    private final AuthenticatedUser authenticatedUser;

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getContext(@PathVariable("jobId") int jobId) {
        String context;
        try {
            context = contextReadService.read(jobId);
        } catch (RuntimeException e) {
            log.warn("Controlled context-read failure for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }

        // A missing object is "not persisted yet", distinct from an empty plan and from an outage.
        if (context == null || context.isBlank()) {
            return new ResponseEntity<>(PENDING_BODY, HttpStatus.OK);
        }

        try {
            return new ResponseEntity<>(contextSanitizer.sanitize(context), HttpStatus.OK);
        } catch (IOException e) {
            log.warn("Controlled failure sanitizing context for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }
    }

    private ResponseEntity<String> unavailable() {
        int retryAfter = contextProperties.getRetryAfterSeconds();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(retryAfter))
                .body("{\"structuredOutputStatus\":{\"state\":\"UNAVAILABLE\"},\"retryAfterSeconds\":" + retryAfter + "}");
    }

    @PostMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> saveContext(@PathVariable("jobId") int jobId, @RequestBody String context) {
        ObjectNode incoming;
        try {
            if (!(objectMapper.readTree(context) instanceof ObjectNode document)) {
                log.warn("Context payload for job {} is not a JSON object", jobId);
                return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
            }
            incoming = document;
        } catch (JacksonException e) {
            log.warn("Invalid context payload for job {}", jobId, e);
            return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
        }

        ContextWriteService.ReplaceResult result;
        try {
            result = contextWriteService.replace(jobId, incoming);
        } catch (ResponseStatusException e) {
            return new ResponseEntity<>("{}", e.getStatusCode());
        } catch (IOException | RuntimeException e) {
            // Includes a context write lock that could not be taken in time.
            log.warn("Controlled failure saving context for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }
        return new ResponseEntity<>(result.saved(), HttpStatus.OK);
    }

    /**
     * Sets individual context entries ({@code {"revision": n, "set": {path: value|null}}}). An entry
     * older than or equal to the one stored is skipped, so a retried or reordered write is a no-op.
     */
    @PostMapping(value = "/{jobId}/patch", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> patchContext(@PathVariable("jobId") int jobId, @RequestBody String patch, Principal principal) {
        if (principal == null) {
            return new ResponseEntity<>("{}", HttpStatus.FORBIDDEN);
        }
        User user = new User(principal);
        if (!authenticatedUser.isServiceAccountInternal(user) && !authenticatedUser.isSuperUser(user)) {
            return new ResponseEntity<>("{}", HttpStatus.FORBIDDEN);
        }
        // Validated before the write lock is taken.
        ContextPatchApplier.Patch parsed;
        try {
            parsed = ContextPatchApplier.parse(objectMapper.readTree(patch), System.currentTimeMillis());
        } catch (JacksonException | InvalidContextPatchException e) {
            log.warn("Invalid context patch for job {}", jobId);
            return new ResponseEntity<>("{}", HttpStatus.BAD_REQUEST);
        }

        ContextWriteService.PatchResult result;
        try {
            result = contextWriteService.patch(jobId, parsed);
        } catch (ResponseStatusException e) {
            return new ResponseEntity<>("{}", e.getStatusCode());
        } catch (IOException | RuntimeException e) {
            log.warn("Controlled failure patching context for job {}: {}", jobId, e.getMessage());
            return unavailable();
        }
        return new ResponseEntity<>("{\"applied\":" + result.applied().size() + ",\"skipped\":" + result.skipped() + "}", HttpStatus.OK);
    }

    @GetMapping(value = "/{jobId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamContext(
            @PathVariable("jobId") String jobId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        SseEmitter emitter = new SseEmitter(0L);
        streamingService.streamJobContextAsync(jobId, emitter, parseResumeId(lastEventId), contextSanitizer);
        return emitter;
    }

    private RecordId parseResumeId(String lastEventId) {
        if (!StringUtils.hasText(lastEventId)) {
            return RecordId.of("0-0");
        }
        try {
            return RecordId.of(lastEventId);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unparseable Last-Event-ID '{}': {}", lastEventId, e.getMessage());
            return RecordId.of("0-0");
        }
    }
}
