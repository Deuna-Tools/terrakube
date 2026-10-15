package io.terrakube.api.plugin.retry;

import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.hooks.workspace.WorkspaceManagementException;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttemptFenceHookTest {
    @Test void rejectsLateOrUnversionedExecutorCallbacks() {
        AuthenticatedUser auth = mock(AuthenticatedUser.class); RequestScope scope = mock(RequestScope.class);
        when(auth.isSuperUser(null)).thenReturn(true);
        AttemptFenceHook fence = new AttemptFenceHook(auth); Job job = new Job(); job.setPlanAttempt(2);
        assertThrows(WorkspaceManagementException.class, () -> fence.execute(null, null, job, scope, Optional.empty()));
        job.setExecutionAttempt(1);
        assertThrows(WorkspaceManagementException.class, () -> fence.execute(null, null, job, scope, Optional.empty()));
        job.setExecutionAttempt(2);
        assertDoesNotThrow(() -> fence.execute(null, null, job, scope, Optional.empty()));
    }
}
