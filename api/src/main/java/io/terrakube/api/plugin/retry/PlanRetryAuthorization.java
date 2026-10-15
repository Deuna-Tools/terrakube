package io.terrakube.api.plugin.retry;

import com.yahoo.elide.core.security.User;
import io.terrakube.api.plugin.security.groups.GroupService;
import io.terrakube.api.plugin.security.rbac.RbacService;
import io.terrakube.api.plugin.security.user.AuthenticatedUser;
import io.terrakube.api.rs.checks.membership.MembershipService;
import io.terrakube.api.rs.job.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PlanRetryAuthorization {
    private final AuthenticatedUser authenticatedUser;
    private final MembershipService membership;
    private final GroupService groups;
    private final RbacService rbac;

    public boolean canView(User user, Job job) {
        return authenticatedUser.isSuperUser(user)
                || membership.checkMembership(user, job.getOrganization().getTeam())
                || membership.checkLimitedMembership(user, job.getWorkspace().getAccess())
                || (job.getWorkspace().getProject() != null && membership.checkProjectMembership(user,
                    job.getWorkspace().getProject().getProjectAccess(), access -> true));
    }

    public boolean canRetry(User user, Job job) {
        if (authenticatedUser.isSuperUser(user)) return true;
        boolean canPlan = job.getOrganization().getTeam().stream()
                .anyMatch(team -> member(user, team.getName()) && rbac.canPlanJob(team))
                || job.getWorkspace().getAccess().stream()
                .anyMatch(access -> member(user, access.getName()) && rbac.canPlanJob(access))
                || (job.getWorkspace().getProject() != null && membership.checkProjectMembership(user,
                    job.getWorkspace().getProject().getProjectAccess(), rbac::canPlanJob));
        // A controller rerun may resume the entire authorized deployment.
        return canPlan && (job.getGithubRunId() == null || member(user, "TERRAKUBE_ADMIN"));
    }

    public boolean isController(User user) { return authenticatedUser.isSuperUser(user); }
    public String actor(User user) { return authenticatedUser.getEmail(user); }

    private boolean member(User user, String name) {
        return authenticatedUser.isServiceAccount(user)
                ? groups.isServiceMember(user, name) : groups.isMember(user, name);
    }
}
