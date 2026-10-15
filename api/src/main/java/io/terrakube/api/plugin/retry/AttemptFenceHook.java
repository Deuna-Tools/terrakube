package io.terrakube.api.plugin.retry;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.hooks.workspace.WorkspaceManagementException;
import io.terrakube.api.rs.job.Job;
import lombok.AllArgsConstructor;
import java.util.Optional;

@AllArgsConstructor
public class AttemptFenceHook implements LifeCycleHook<Job> {
    private final AuthenticatedUser authenticatedUser;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation,
            LifeCycleHookBinding.TransactionPhase phase, Job job, RequestScope scope,
            Optional<ChangeSpec> change) {
        if (authenticatedUser.isSuperUser(scope.getUser()) && job.getPlanAttempt() > 1
                && !java.util.Set.of(io.terrakube.api.rs.job.JobStatus.approved,
                        io.terrakube.api.rs.job.JobStatus.rejected, io.terrakube.api.rs.job.JobStatus.cancelled).contains(job.getStatus())
                && !Integer.valueOf(job.getPlanAttempt()).equals(job.getExecutionAttempt())) {
            throw new WorkspaceManagementException(409, "Executor callback belongs to an older plan attempt");
        }
    }
}
