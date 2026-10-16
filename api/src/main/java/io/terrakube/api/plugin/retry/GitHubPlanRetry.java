package io.terrakube.api.plugin.retry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.job.Job;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Objects;

/** Durable dispatch: reserve once, then reconcile an uncertain response instead
 * of issuing another mutation. Credentials never leave the API process. */
@Component
@RequiredArgsConstructor
@Slf4j
public class GitHubPlanRetry {
    private final JobRepository jobs;
    private final VariableRepository variables;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    @Value("${io.terrakube.retry.repository:}") private String repository;
    @Value("${io.terrakube.retry.github-token:}") private String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public boolean enabledFor(Job job) {
        return repository != null && !repository.isBlank() && token != null && !token.isBlank()
                && repository.equals(job.getGithubRepository());
    }

    public JsonNode validate(Job job) {
        if (!enabledFor(job)) fail("GitHub retry is not configured for this repository");
        if (!"true".equals(get("actions/variables/TERRAKUBE_PLAN_RETRY_ENABLED").path("value").asText()))
            fail("The repository retry controller is not enabled");
        if (job.getPrNumber() == null || job.getPrNumber() <= 0) fail("The pull request association is missing");
        JsonNode pr = get("pulls/" + job.getPrNumber());
        if (!"open".equals(pr.path("state").asText()) || pr.path("draft").asBoolean()
                || !repository.equals(pr.path("head").path("repo").path("full_name").asText())
                || !job.getCommitId().equals(pr.path("head").path("sha").asText())) fail("The pull request or its commit changed");
        String rawLock = variables.findByWorkspace(job.getWorkspace()).orElse(java.util.List.of()).stream()
                .filter(variable -> "TERRAKUBE_PR_LOCK_OWNER".equals(variable.getKey()))
                .map(variable -> variable.getValue()).findFirst().orElse("");
        try {
            JsonNode owner = json.readTree(rawLock);
            if (owner == null || owner.path("pr").asInt() != job.getPrNumber()
                    || !job.getCommitId().equals(owner.path("sha").asText())) fail("Workspace ownership changed");
        } catch (java.io.IOException error) { fail("Workspace ownership cannot be verified"); }
        JsonNode actionJob = get("actions/jobs/" + job.getGithubJobId());
        if (actionJob.path("run_id").asLong() != job.getGithubRunId()) fail("The GitHub job does not belong to this workflow run");
        return get("actions/runs/" + job.getGithubRunId());
    }

    public long controllerJob(long runId) {
        if (repository == null || repository.isBlank() || token == null || token.isBlank()) fail("GitHub retry is not configured");
        java.util.List<Long> matches = new java.util.ArrayList<>();
        for (JsonNode job : get("actions/runs/" + runId + "/jobs?filter=latest&per_page=100").path("jobs")) {
            if (!"in_progress".equals(job.path("status").asText())) continue;
            for (JsonNode step : job.path("steps")) {
                if ("Run affected Terrakube stacks".equals(step.path("name").asText())
                        && "in_progress".equals(step.path("status").asText())) matches.add(job.path("id").asLong());
            }
        }
        if (matches.size() != 1) fail("Cannot uniquely identify the active controller job");
        return matches.getFirst();
    }

    @Scheduled(fixedDelayString = "${io.terrakube.retry.poll-ms:5000}")
    public void dispatchRequested() {
        if (repository == null || repository.isBlank() || token == null || token.isBlank()) return;
        for (Job candidate : jobs.findByRetryRequestedTrue()) {
            if (candidate.getGithubRunId() == null) continue;
            if (java.util.Set.of("sent", "error").contains(Objects.toString(candidate.getRetryDispatchState(), ""))) continue;
            try { dispatch(candidate.getId()); }
            catch (Exception error) { log.warn("GitHub retry reconciliation for job {} is pending", candidate.getId()); }
        }
    }

    void dispatch(int id) {
        Job snapshot = jobs.findById(id).orElseThrow();
        JsonNode run;
        try { run = validate(snapshot); }
        catch (ResponseStatusException error) {
            if (error.getStatusCode().value() >= 500) return; // Read-only validation may be retried.
            new TransactionTemplate(transactions).execute(status -> {
                Job job = jobs.lockForUpdate(id);
                if (job.isRetryRequested()) { job.setRetryDispatchState("error"); job.setRetryMessage(error.getReason()); jobs.save(job); }
                return null;
            });
            return;
        }
        if (snapshot.getGithubRetryAttempt() != null
                && run.path("run_attempt").asInt() >= snapshot.getGithubRetryAttempt()) {
            new TransactionTemplate(transactions).execute(status -> {
                Job job = jobs.lockForUpdate(id);
                if (job.isRetryRequested()) { job.setRetryDispatchState("sent"); job.setRetryMessage("GitHub resumed; waiting for the controller"); jobs.save(job); }
                return null;
            });
            return;
        }
        if (!"completed".equals(run.path("status").asText())) return;
        boolean reserved = Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> {
            Job job = jobs.lockForUpdate(id);
            if (!job.isRetryRequested() || !"waiting".equals(job.getRetryDispatchState())) return false;
            job.setGithubRetryAttempt(run.path("run_attempt").asInt() + 1);
            job.setRetryDispatchState("sending"); job.setRetryMessage("Resuming GitHub Actions"); jobs.save(job);
            return true;
        }));
        if (!reserved) return;
        // No automatic POST retry: a timeout may mean GitHub accepted the rerun.
        try {
            HttpResponse<String> response = http.send(request("actions/jobs/" + snapshot.getGithubJobId() + "/rerun")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
            new TransactionTemplate(transactions).execute(status -> {
                Job job = jobs.lockForUpdate(id);
                if (job.isRetryRequested()) {
                    boolean accepted = response.statusCode() == 201 || response.statusCode() == 202;
                    job.setRetryDispatchState(accepted ? "sent" : "error");
                    job.setRetryMessage(accepted ? "GitHub rerun accepted; waiting for the controller"
                            : "GitHub rejected the rerun (HTTP " + response.statusCode() + "); inspect Actions before retrying");
                    jobs.save(job);
                }
                return null;
            });
        } catch (Exception uncertain) {
            if (uncertain instanceof InterruptedException) Thread.currentThread().interrupt();
            new TransactionTemplate(transactions).execute(status -> {
                Job job = jobs.lockForUpdate(id);
                if (job.isRetryRequested()) { job.setRetryMessage("GitHub rerun outcome is uncertain; inspect Actions before retrying"); jobs.save(job); }
                return null;
            });
            log.warn("GitHub rerun outcome for job {} is uncertain; no second dispatch will be sent", id);
        }
    }

    private JsonNode get(String path) {
        try {
            HttpResponse<String> response = http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 500) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "GitHub validation is temporarily unavailable");
            if (response.statusCode() != 200) fail("GitHub validation failed (HTTP " + response.statusCode() + ")");
            return json.readTree(response.body());
        } catch (ResponseStatusException error) { throw error; }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub is unavailable; retry was not dispatched");
        }
    }

    private HttpRequest.Builder request(String path) {
        // Repository is server-configured; callers cannot choose a URL or recipient.
        return HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repository + "/" + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json");
    }

    private static void fail(String message) { throw new ResponseStatusException(HttpStatus.CONFLICT, message); }
}
