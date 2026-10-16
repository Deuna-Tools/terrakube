package io.terrakube.api.repository;

import io.terrakube.api.rs.job.step.Step;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StepRepository extends JpaRepository<Step, UUID> {

    @org.springframework.data.jpa.repository.Query("select s from step s where s.job.id = :jobId and s.executionAttempt = s.job.planAttempt")
    List<Step> findByJobId(@org.springframework.data.repository.query.Param("jobId") int jobId);

    @org.springframework.data.jpa.repository.Query("select s from step s where s.job.id = :jobId order by s.executionAttempt, s.stepNumber")
    List<Step> findAllAttemptsByJobId(@org.springframework.data.repository.query.Param("jobId") int jobId);

    default Optional<Step> findFirstByJobIdOrderByStepNumber(int jobId) {
        return findByJobId(jobId).stream().min(java.util.Comparator.comparingInt(Step::getStepNumber));
    }
}
