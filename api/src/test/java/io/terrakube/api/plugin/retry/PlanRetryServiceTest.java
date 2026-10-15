package io.terrakube.api.plugin.retry;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.context.ContextSanitizer;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.subscription.JobStatusPublisher;
import io.terrakube.api.repository.*;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanRetryServiceTest {
    JobRepository jobs = mock(JobRepository.class);
    StepRepository steps = mock(StepRepository.class);
    WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    PlanAttemptRepository attempts = mock(PlanAttemptRepository.class);
    PlanRetryAuthorization auth = mock(PlanRetryAuthorization.class);
    GitHubPlanRetry github = mock(GitHubPlanRetry.class);
    StorageTypeService storage = mock(StorageTypeService.class);
    ContextSanitizer sanitizer = mock(ContextSanitizer.class);
    ScheduleJobService scheduler = mock(ScheduleJobService.class);
    RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
    User user = mock(User.class);
    PlanRetryService service;
    Job job;
    Step plan;

    @BeforeEach
    void setup() throws Exception {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(anyString(), any(), any(java.time.Duration.class))).thenReturn(true);
        service = new PlanRetryService(jobs, steps, workspaces, attempts, mock(VariableRepository.class), auth,
                github, storage, sanitizer, scheduler, mock(JobStatusPublisher.class), redis, tx);
        Workspace workspace = new Workspace(); workspace.setId(UUID.randomUUID());
        Organization org = new Organization(); org.setId(UUID.randomUUID());
        job = new Job(); job.setId(7087); job.setWorkspace(workspace); job.setOrganization(org);
        job.setStatus(JobStatus.failed); job.setCommitId("a".repeat(40));
        job.setTcl(Base64.getEncoder().encodeToString("flow:\n  - type: terraformPlan\n    step: 100\n".getBytes()));
        plan = new Step(); plan.setId(UUID.randomUUID()); plan.setJob(job); plan.setStepNumber(100);
        plan.setName("Plan"); plan.setStatus(JobStatus.failed); plan.setOutput("old-log-url");
        when(jobs.findById(7087)).thenReturn(Optional.of(job));
        when(jobs.findAllByWorkspaceAndStatusNotInOrderByIdAsc(any(), any())).thenReturn(Optional.of(List.of()));
        when(jobs.findByWorkspaceOrderByIdAsc(any())).thenReturn(List.of(job));
        when(steps.findByJobId(7087)).thenReturn(List.of(plan));
        when(auth.canView(user, job)).thenReturn(true); when(auth.canRetry(user, job)).thenReturn(true);
        when(auth.actor(user)).thenReturn("operator@example.test");
        when(storage.getContext(7087)).thenReturn("{}"); when(sanitizer.sanitize("{}")).thenReturn("{}");
    }

    @Test void standaloneRetryPreservesIdAndOldStepAndStartsFreshAttempt() throws Exception {
        service.request(7087, 1, user);
        assertEquals(7087, job.getId()); assertEquals(2, job.getPlanAttempt());
        assertEquals(JobStatus.pending, job.getStatus()); assertNotNull(job.getPlanAttemptStarted());
        assertNull(job.getTerraformPlan()); assertNull(job.getApprovalTeam());
        assertEquals(JobStatus.failed, plan.getStatus()); assertEquals("old-log-url", plan.getOutput());
        ArgumentCaptor<Step> created = ArgumentCaptor.forClass(Step.class);
        verify(steps).save(created.capture());
        assertNull(created.getValue().getId()); assertEquals(2, created.getValue().getExecutionAttempt());
        assertEquals(JobStatus.pending, created.getValue().getStatus()); assertNull(created.getValue().getOutput());
        verify(attempts).save(any(PlanAttempt.class)); verify(scheduler).createJobContext(job);
    }

    @Test void staleAttemptDoesNotScheduleAnything() {
        assertThrows(ResponseStatusException.class, () -> service.request(7087, 0, user));
        verifyNoInteractions(attempts, scheduler);
    }

    @Test void readOnlyUserCannotRequestRetry() {
        when(auth.canRetry(user, job)).thenReturn(false);
        assertEquals(403, assertThrows(ResponseStatusException.class, () -> service.request(7087, 1, user)).getStatusCode().value());
        verifyNoInteractions(attempts, scheduler);
    }

    @Test void anotherActiveRunBlocksRetry() {
        Job other = new Job(); other.setId(7090); other.setStatus(JobStatus.running);
        when(jobs.findAllByWorkspaceAndStatusNotInOrderByIdAsc(any(), any())).thenReturn(Optional.of(List.of(other)));
        assertNotNull(service.unavailable(job));
    }

    @Test void runningStepBlocksRetry() {
        plan.setStatus(JobStatus.running); assertEquals("A step is still active", service.unavailable(job));
    }

    @Test void executedApplyBlocksRetry() {
        job.setTcl(Base64.getEncoder().encodeToString(("flow:\n  - type: terraformPlan\n    step: 100\n"
                + "  - type: terraformApply\n    step: 200\n").getBytes()));
        Step apply = new Step(); apply.setStepNumber(200); apply.setStatus(JobStatus.failed); apply.setOutput("apply-log");
        when(steps.findByJobId(7087)).thenReturn(List.of(plan, apply));
        assertEquals("A downstream step already executed", service.unavailable(job));
    }

    @Test void githubRetryIsReservedOnceAndNotScheduledByTheUi() {
        managed();
        service.request(7087, 1, user); service.request(7087, 1, user);
        assertTrue(job.isRetryRequested()); assertEquals(1, job.getPlanAttempt());
        verify(github, times(1)).validate(job); verifyNoInteractions(attempts, scheduler);
    }

    @Test void onlyMatchingControllerCanActivateManagedRetry() {
        managed(); job.setRetryRequested(true);
        assertThrows(ResponseStatusException.class, () -> service.activate(7087, 1, user, 10));
        when(auth.isController(user)).thenReturn(true);
        assertThrows(ResponseStatusException.class, () -> service.activate(7087, 1, user, 11));
        verifyNoInteractions(attempts, scheduler);
    }

    @Test void newerWorkspaceRunBlocksHistoricalPlanRetry() {
        managed(); Job newer = new Job(); newer.setId(7090);
        when(jobs.findByWorkspaceOrderByIdAsc(any())).thenReturn(List.of(job, newer));
        assertEquals("A later run already superseded this plan", service.unavailable(job));
    }

    @Test void rejectionIsNotRetryable() {
        job.setStatus(JobStatus.rejected); assertNotNull(service.unavailable(job));
    }

    @Test void aLegacyPrRunCannotRetryWithoutUpdatingActions() {
        job.setPrNumber(42);
        assertTrue(service.unavailable(job).contains("predates GitHub retry metadata"));
    }

    @Test void duplicateActivationDoesNotRescheduleAnAlreadyStartedAttempt() throws Exception {
        job.setPlanAttempt(2); job.setRetryRequested(false); job.setStatus(JobStatus.pending);
        service.activate(7087, 1, user, 0);
        verifyNoInteractions(attempts, scheduler);
    }

    private void managed() {
        job.setGithubRunId(10L); job.setGithubJobId(20L); job.setGithubPhase("plan");
        when(github.enabledFor(job)).thenReturn(true);
    }
}
