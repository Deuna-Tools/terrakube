package io.terrakube.api.plugin.retry;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.context.ContextSanitizer;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.subscription.JobStatusEvent;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class PlanRetryService {
    private static final Set<JobStatus> ACTIVE = EnumSet.of(JobStatus.pending, JobStatus.approved,
            JobStatus.waitingApproval, JobStatus.queue, JobStatus.running);
    private final JobRepository jobs;
    private final StepRepository steps;
    private final WorkspaceRepository workspaces;
    private final PlanAttemptRepository attempts;
    private final VariableRepository variables;
    private final PlanRetryAuthorization authorization;
    private final GitHubPlanRetry github;
    private final StorageTypeService storage;
    private final ContextSanitizer sanitizer;
    private final ScheduleJobService scheduler;
    private final JobStatusPublisher publisher;
    private final RedisTemplate<String, Object> redis;
    private final PlatformTransactionManager transactions;

    public Map<String, Object> describe(int id, User user) {
        return new TransactionTemplate(transactions).execute(status -> {
            Job job = visible(id, user);
            String reason = authorization.canRetry(user, job) ? unavailable(job) : "Plan retry permission is required";
            List<Step> historySteps = steps.findAllAttemptsByJobId(id);
            List<Map<String, Object>> history = attempts.findByJobIdOrderByAttemptDesc(id).stream().map(attempt -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("attempt", attempt.getAttempt()); item.put("status", attempt.getStatus());
                item.put("finishedAt", attempt.getFinishedAt()); item.put("retriedBy", attempt.getRetriedBy());
                item.put("steps", historySteps.stream()
                        .filter(step -> step.getExecutionAttempt() == attempt.getAttempt())
                        .map(step -> Map.of("id", step.getId(), "name", step.getName(),
                                "status", step.getStatus(), "output", Objects.toString(step.getOutput(), ""))).toList());
                return item;
            }).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("attempt", job.getPlanAttempt()); result.put("available", reason == null);
            result.put("reason", reason); result.put("requested", job.isRetryRequested());
            result.put("message", job.getRetryMessage()); result.put("history", history);
            return result;
        });
    }

    public void request(int id, int expectedAttempt, User user) {
        locked(id, () -> new TransactionTemplate(transactions).execute(status -> {
            Job job = visible(id, user);
            workspaces.lockForPlanRetry(job.getWorkspace().getId());
            if (!authorization.canRetry(user, job)) forbidden();
            if (job.getPlanAttempt() != expectedAttempt) conflict("The plan attempt changed; refresh the run");
            if (job.isRetryRequested()) return null; // Idempotent double-click / uncertain response.
            String reason = unavailable(job);
            if (reason != null) conflict(reason);
            if (job.getGithubRunId() != null) github.validate(job);
            job.setRetryRequested(true);
            job.setGithubRetryAttempt(null);
            job.setRetryDispatchState("waiting");
            job.setRetryMessage("Retry requested by " + authorization.actor(user));
            jobs.save(job);
            return null;
        }));
        // Standalone UI runs have no GitHub controller to reattach.
        Job job = jobs.findById(id).orElseThrow();
        if (job.getGithubRunId() == null) activate(id, expectedAttempt, user, 0);
    }

    public void activate(int id, int expectedAttempt, User user, long githubRunId) {
        locked(id, () -> {
            boolean activated = Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(status -> {
                Job job = visible(id, user);
                workspaces.lockForPlanRetry(job.getWorkspace().getId());
                if (job.getGithubRunId() != null && (!authorization.isController(user)
                        || job.getGithubRunId() != githubRunId)) forbidden();
                if (job.getGithubRunId() == null && !authorization.canRetry(user, job)) forbidden();
                if (job.getPlanAttempt() == expectedAttempt + 1 && !job.isRetryRequested()) return false;
                if (!job.isRetryRequested() || job.getPlanAttempt() != expectedAttempt) conflict("No matching retry request");
                String reason = unavailable(job);
                if (reason != null) conflict(reason);
                if (job.getGithubRunId() != null) github.validate(job);
                PlanAttempt archive = new PlanAttempt();
                archive.setJob(job); archive.setAttempt(job.getPlanAttempt());
                archive.setStatus(job.getStatus().name()); archive.setFinishedAt(new Date());
                archive.setRetriedBy(job.getRetryMessage());
                try { archive.setContext(sanitizer.sanitize(Objects.toString(storage.getContext(id), "{}"))); }
                catch (Exception error) { conflict("Cannot archive the previous attempt; retry was not started"); }
                attempts.save(archive);
                List<Step> previous = steps.findByJobId(id);
                job.setPlanAttempt(job.getPlanAttempt() + 1);
                job.setPlanAttemptStarted(new Date());
                for (Step old : previous) {
                    Step step = new Step(); step.setJob(job); step.setName(old.getName());
                    step.setStepNumber(old.getStepNumber()); step.setExecutionAttempt(job.getPlanAttempt());
                    step.setStatus(JobStatus.pending); steps.save(step);
                }
                // Each attempt gets fresh step IDs; all previous log objects stay addressable.
                storage.saveContext(id, "{}"); redis.delete(String.valueOf(id));
                job.setOutput(null); job.setTerraformPlan(null); job.setApprovalTeam(null);
                job.setPlanChanges(true); job.setStatus(JobStatus.pending);
                job.setRetryRequested(false); job.setRetryDispatchState(null); job.setRetryMessage(null);
                jobs.save(job);
                return true;
            }));
            if (!activated) return null;
            Job job = jobs.findById(id).orElseThrow();
            try { scheduler.createJobContext(job); }
            catch (org.quartz.ObjectAlreadyExistsException existing) {
                try { scheduler.createJobContextNow(job); } catch (Exception error) { conflict("Retry queued; scheduler recovery is pending"); }
            } catch (Exception error) { conflict("Retry queued; scheduler recovery is pending"); }
            publisher.publish(new JobStatusEvent(id, job.getWorkspace().getId().toString(), job.getStatus().name()),
                    job.getOrganization().getId().toString());
            return null;
        });
    }

    String unavailable(Job job) {
        if (!Set.of(JobStatus.failed, JobStatus.cancelled).contains(job.getStatus())) return "Only failed or canceled plans can be retried";
        if (job.getCommitId() == null || !job.getCommitId().matches("[0-9a-f]{40}")) return "The original commit is not recorded";
        if (job.getWorkspace().isLocked()) return "The workspace is locked";
        if (job.getPrNumber() != null && job.getPrNumber() > 0 && job.getGithubRunId() == null)
            return "This PR run predates GitHub retry metadata; start a new authorized PR run";
        if (jobs.findAllByWorkspaceAndStatusNotInOrderByIdAsc(job.getWorkspace(),
                List.of(JobStatus.completed, JobStatus.failed, JobStatus.cancelled, JobStatus.rejected, JobStatus.noChanges, JobStatus.notExecuted))
                .orElse(List.of()).stream().anyMatch(other -> other.getId() != job.getId())) return "Another run is active on this workspace";
        List<Map<String, Object>> flow;
        try {
            Map<?, ?> tcl = new Yaml(new SafeConstructor(new LoaderOptions())).load(
                    new String(Base64.getDecoder().decode(job.getTcl()), StandardCharsets.UTF_8));
            @SuppressWarnings("unchecked") List<Map<String, Object>> parsed = (List<Map<String, Object>>) tcl.get("flow");
            flow = parsed;
            if (flow == null || flow.isEmpty() || !"terraformPlan".equals(flow.getFirst().get("type"))) return "This run does not start with a plan";
        } catch (Exception invalid) { return "The original template is unavailable"; }
        List<Step> current = steps.findByJobId(job.getId());
        if (current.isEmpty()) return "The plan steps are not recorded";
        boolean failedPlan = false;
        for (Step step : current) {
            if (ACTIVE.contains(step.getStatus())) return "A step is still active";
            String type = flow.stream().filter(item -> Integer.toString(step.getStepNumber()).equals(Objects.toString(item.get("step"))))
                    .map(item -> Objects.toString(item.get("type"))).findFirst().orElse("");
            if ("terraformPlan".equals(type) && Set.of(JobStatus.failed, JobStatus.cancelled).contains(step.getStatus())) failedPlan = true;
            if (!"terraformPlan".equals(type) && (step.getOutput() != null || step.getStatus() == JobStatus.completed)) return "A downstream step already executed";
        }
        if (!failedPlan) return "No failed plan step was found";
        if (job.getGithubRunId() != null) {
            if (!"plan".equals(job.getGithubPhase()) || job.getGithubJobId() == null) return "This run has no supported GitHub plan association";
            if (jobs.findByWorkspaceOrderByIdAsc(job.getWorkspace()).stream()
                    .anyMatch(other -> other.getId() > job.getId())) return "A later run already superseded this plan";
            if (!github.enabledFor(job)) return "GitHub retry integration is not configured";
        }
        return null;
    }

    private Job visible(int id, User user) {
        Job job = jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!authorization.canView(user, job)) forbidden();
        return job;
    }

    private <T> T locked(int id, Supplier<T> work) {
        String key = "job-execution-lock:" + id, owner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, Duration.ofMinutes(5)))) conflict("Run is busy; refresh before retrying");
        try { return work.get(); }
        finally { redis.execute(new DefaultRedisScript<Long>("if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else return 0 end", Long.class), List.of(key), owner); }
    }

    private static void forbidden() { throw new ResponseStatusException(HttpStatus.FORBIDDEN); }
    private static void conflict(String reason) { throw new ResponseStatusException(HttpStatus.CONFLICT, reason); }
}
