# Exact-plan review runtime upgrade

The Athia review gate stays disabled until the API and executor pass the live
checks below. Keep the existing human approval step. This release adds no database
migration and does not change state, backend paths, or the default Apply behavior.

## Changes

- Backport upstream #3639 (`5ff2d922c0dd8ad497038dfdc0e207e66d77d58f`).
  A step with `requireSavedPlan: true` fails before Apply if its plan download
  fails or the downloaded artifact is absent, empty, or not a regular file.
  The default remains false for existing templates.
- Backport the API patch/locking portion of upstream #3702
  (`f20d14cf8f1073cb6d2ce7963eb81a2dea7a22d9`). A patch updates only its named
  entries. Stale revisions are skipped. Legacy full-document writes preserve
  stored revisioned entries, including the Athia review proof. The existing
  executor write format remains supported.
- `POST /context/v1/{jobId}/patch` requires an internal service token or Terrakube
  administrator. Its body is `{"revision": positive_long, "set": {"key": value}}`.
  Paths support a top-level key or `key/child`. Null deletes an entry. The API
  returns applied/skipped counts. Callers must check the result and read back the
  proof before they treat a write as successful.

## Release and rollout

1. Require the PR backend build, including real Redis lock tests, to pass.
2. Build a new immutable DEUNA release from the merged commit with the existing
   Manual Terrakube Release workflow. Copy that exact release to Ops ECR and
   verify its digests. Never overwrite an existing release tag.
3. Prepare an `infra-develop` PR in terraform-devops for
   `operations/terrakube` and validate its exact commit in the existing
   `operations-terrakube` workspace with the trusted plan-only template.
   Do not post `/terrakube-runs` during this validation. Inspect the plan for
   unexpected deletes, replacements, storage, identity, or backend changes.
4. Record the current image versions and digests for rollback. Keep
   `TERRAKUBE_ATHIA_REVIEW_ENABLED=false`. Wait for active Terraform jobs to finish
   before replacing executor pods. Do not cancel jobs or unlock the workspace.
5. Deploy through the existing Plan / human approval / Apply path. A self-upgrade
   can restart the executor that runs it; observe Helm rollout and reconcile the
   run result before any second deployment. Do not use a failed job status alone
   as evidence that Helm made no change.
6. Verify every API and executor pod uses the new release and is ready. Mixed old
   and new API pods are not safe for review-proof persistence: old pods do not
   take the context write lock. Complete the rollout before enabling the gate.
7. Run an isolated plan-only Athia review with proof persistence. Verify the exact
   PR SHA, plan attempt, binary digest, structured evidence digest, model, and
   stored PASS proof. Confirm a legacy context write cannot remove the proof.
   Use a disposable test job, never a job that can Apply for this probe.
8. Confirm the guarded template still includes human approval and
   `requireSavedPlan: true`. Test a missing saved plan in an isolated fixture;
   Apply must not run. Enable the review gate only after these checks pass.

The API uses a per-job Redis lock with a 60-second lease. The full storage
read/write must finish within that lease. Ops S3-compatible storage uses bounded
SDK calls (10 seconds by default). Verify the deployed timeout settings before
rollout. A storage system with longer or unbounded writes needs conditional
object-store writes before it can use this protocol safely.

## Rollback

Disable the review gate first. Drain active jobs, then restore the recorded image
release through the same reviewed Terraform path and human approval gate.
Version 2.33.6 cannot enforce `requireSavedPlan` or protect atomic review proof;
keep the review gate disabled on that version. No database rollback is needed.
