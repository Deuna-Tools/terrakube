package io.terrakube.api.plugin.retry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlanRetryControllerTest {
    private final PlanRetryService retries = mock(PlanRetryService.class);
    private final Authentication authentication = mock(Authentication.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PlanRetryController(retries)).build();
    }

    @Test
    void acceptsTheUiRequestWithoutGitHubMetadata() throws Exception {
        mvc.perform(post("/api/retry/v1/jobs/7087/plan")
                .principal(authentication).contentType("application/json")
                .content("{\"expectedAttempt\":1}"))
                .andExpect(status().isAccepted());
        verify(retries).request(eq(7087), eq(1), any());
    }

    @Test
    void activationPassesTheControllerAssociation() throws Exception {
        mvc.perform(post("/api/retry/v1/jobs/7087/activate")
                .principal(authentication).contentType("application/json")
                .content("{\"expectedAttempt\":1,\"githubRunId\":1234}"))
                .andExpect(status().isAccepted());
        verify(retries).activate(eq(7087), eq(1), any(), eq(1234L));
    }

    @Test
    void activationStillRequiresGitHubMetadata() throws Exception {
        mvc.perform(post("/api/retry/v1/jobs/7087/activate")
                .principal(authentication).contentType("application/json")
                .content("{\"expectedAttempt\":1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(retries);
    }
}
