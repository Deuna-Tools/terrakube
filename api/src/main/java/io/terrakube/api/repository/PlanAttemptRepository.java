package io.terrakube.api.repository;

import io.terrakube.api.plugin.retry.PlanAttempt;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PlanAttemptRepository extends JpaRepository<PlanAttempt, UUID> {
    List<PlanAttempt> findByJobIdOrderByAttemptDesc(int jobId);
}
