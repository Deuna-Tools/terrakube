# Retry a failed plan before apply

The run page offers **Retry plan** for a failed or canceled plan when no
approval, apply, destroy, or custom downstream step has executed. The retry
keeps the run ID, commit, template snapshot, and previous step logs. A new
attempt receives new step IDs and a new six-hour execution window. Old
executor callbacks cannot change the new attempt's job status.

The API checks plan permission, workspace activity, and the expected attempt
number. A workspace with a newer run cannot resume an older managed plan.
Plan retries are not an apply retry mechanism. Existing Terraform locking and
approval gates still apply.

## GitHub controller integration

The trusted controller records `githubRepository`, `githubRunId`, `githubJobId`,
`githubStack`, `githubPhase`, and the exact `commitId` when it creates a run.
Only the super-service identity can write these associations. Existing jobs
without that metadata cannot resume an Actions workflow through this feature.

Set `PlanRetryRepository` to the repository that owns the controller, and
`PlanRetryGitHubToken` through a Kubernetes secret reference. The token needs
repository read access, Actions write access, and access to the repository
variable `TERRAKUBE_PLAN_RETRY_ENABLED`. Set that variable to `true` only after
the compatible API, executor, UI, and recovery-aware controller are deployed.
No token is returned to the browser or included in a job's workspace variables.

For managed runs, the requester also needs `TERRAKUBE_ADMIN` membership: an
Actions rerun can resume the full previously-authorized deployment. Requesting
the retry does not approve a run. The API checks the current open PR, head SHA,
workspace ownership, repository flag, and Actions job/run association.

The API stores the request before dispatching. An active controller can consume
it directly. If the controller has finished, a background worker reruns its
Actions job and dependent jobs. The new controller execution activates the
requested plan attempt and reuses the previously recorded phase jobs. A failed
preliminary Ohio plan is not retryable; the controller creates a fresh Ohio
plan after the prerequisite wave as before.

GitHub allows reruns for 30 days and at most 50 reruns. A rejected dispatch is
shown on the run page. If the HTTP response is lost, the worker checks the
workflow's attempt number and never blindly submits another rerun. Inspect
Actions before manually retrying an uncertain dispatch. A manually rerun
controller can consume the already-stored retry request.

## API

- `GET /api/retry/v1/jobs/{id}`: permission-aware availability and attempt history.
- `POST /api/retry/v1/jobs/{id}/plan`: request a retry with `expectedAttempt`.
- `POST /api/retry/v1/jobs/{id}/activate`: controller-only activation for a managed
  job, with `expectedAttempt` and the recorded `githubRunId`.
- `GET /api/retry/v1/actions/{runId}/controller`: super-service-only lookup of the
  active controller job. This avoids expanding the caller workflow's token grants.

Standalone UI jobs activate after reservation without an Actions dispatch.
The execution counter is server-owned. Executor job-status callbacks carry
`executionAttempt`; stale or unversioned callbacks are rejected after a retry.
Deploy API and executor together before enabling retries.

## Portal assets

Build the UI image with `VITE_BASE=/terrakube/` for the DEUNA portal. The manual
GHCR release workflow exposes this as `ui_base_path`. Sidebar and login logos
also use the runtime base-path helper. The portal proxy must not prefix already
prefixed asset URLs. The fork's CI verifies builds without using the upstream
Sonar project; the inherited Docker Hub release workflow does not run in DEUNA.

## DEUNA ECR distribution

The manual release workflow also accepts `publish_to_ecr=true` with an existing
release version. It copies only the Terrakube API, executor, and UI to private
Operations ECR repositories ending in `-ops`, verifies their image digests,
and never rebuilds or republishes the source GHCR release in this mode.

GitHub Actions uses its short-lived repository token to read its own packages
and the existing `RoleForGithubPushECRAllService` OIDC role to push ECR images.
The cluster uses AWS IAM to pull those copies. No change to a shared personal
access token, Jarvis deployment, IAM trust policy, or registry visibility is
needed. Provision the three ECR repositories through the control-plane stack
before dispatching this mode.
