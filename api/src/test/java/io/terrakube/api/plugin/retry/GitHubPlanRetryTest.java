package io.terrakube.api.plugin.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.VariableRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.parameters.Variable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubPlanRetryTest {
    private static final String REPO = "DUNA-E-Commmerce/terraform-devops";
    JobRepository jobs = mock(JobRepository.class);
    VariableRepository variables = mock(VariableRepository.class);
    HttpClient http = mock(HttpClient.class);
    GitHubPlanRetry retry;
    Job job;
    AtomicInteger posts = new AtomicInteger();
    int runAttempt = 1;
    boolean uncertain;
    String head = "a".repeat(40);
    long actionRun = 123;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        retry = new GitHubPlanRetry(jobs, variables, new ObjectMapper(), tx);
        ReflectionTestUtils.setField(retry, "repository", REPO);
        ReflectionTestUtils.setField(retry, "token", "test-token");
        ReflectionTestUtils.setField(retry, "http", http);
        job = new Job(); job.setId(7087); job.setPrNumber(42); job.setCommitId(head);
        job.setGithubRepository(REPO); job.setGithubRunId(123L); job.setGithubJobId(456L);
        job.setWorkspace(new Workspace()); job.setRetryRequested(true); job.setRetryDispatchState("waiting");
        Variable owner = new Variable(); owner.setKey("TERRAKUBE_PR_LOCK_OWNER");
        owner.setValue("{\"pr\":42,\"sha\":\"" + head + "\"}");
        when(variables.findByWorkspace(any())).thenReturn(Optional.of(List.of(owner)));
        when(jobs.findById(7087)).thenReturn(Optional.of(job)); when(jobs.lockForUpdate(7087)).thenReturn(job);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpResponse<String> response = mock(HttpResponse.class);
            if (request.method().equals("POST")) {
                posts.incrementAndGet();
                if (uncertain) throw new IOException("response lost");
                when(response.statusCode()).thenReturn(201); when(response.body()).thenReturn("");
            } else {
                when(response.statusCode()).thenReturn(200);
                String path = request.uri().getPath();
                String body;
                if (path.contains("/variables/")) body = "{\"value\":\"true\"}";
                else if (path.contains("/pulls/")) body = "{\"state\":\"open\",\"head\":{\"sha\":\"" + head + "\",\"repo\":{\"full_name\":\"" + REPO + "\"}}}";
                else if (path.contains("/jobs/")) body = "{\"run_id\":" + actionRun + "}";
                else body = "{\"status\":\"completed\",\"run_attempt\":" + runAttempt + "}";
                when(response.body()).thenReturn(body);
            }
            return response;
        });
    }

    @Test void reservesAndDispatchesExactlyOnce() {
        retry.dispatch(7087); retry.dispatch(7087);
        assertEquals(1, posts.get()); assertEquals("sent", job.getRetryDispatchState());
        assertEquals(2, job.getGithubRetryAttempt());
    }

    @Test void lostPostResponseIsReconciledWithoutSendingAnotherRerun() {
        uncertain = true; retry.dispatch(7087);
        assertEquals("sending", job.getRetryDispatchState());
        retry.dispatch(7087); assertEquals(1, posts.get());
        runAttempt = 2; retry.dispatch(7087);
        assertEquals("sent", job.getRetryDispatchState()); assertEquals(1, posts.get());
    }

    @Test void changedPrCommitCannotBeDispatched() {
        head = "b".repeat(40); retry.dispatch(7087);
        assertEquals(0, posts.get()); assertEquals("error", job.getRetryDispatchState());
    }

    @Test void mismatchedActionsJobCannotBeDispatched() {
        actionRun = 999; retry.dispatch(7087);
        assertEquals(0, posts.get()); assertEquals("error", job.getRetryDispatchState());
    }

    @Test void anUnconfiguredRepositoryNeverReceivesTheToken() {
        job.setGithubRepository("other/repo");
        assertThrows(ResponseStatusException.class, () -> retry.validate(job));
        verifyNoInteractions(http);
    }

    @Test @SuppressWarnings("unchecked") void aTemporaryGitHubReadFailureLeavesTheRequestPending() throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(503);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        retry.dispatch(7087);
        assertEquals("waiting", job.getRetryDispatchState());
        assertEquals(0, posts.get());
    }
}
