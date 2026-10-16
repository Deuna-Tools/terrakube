package io.terrakube.api.plugin.retry;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.util.Date;
import java.util.UUID;

@Entity(name = "plan_attempt")
@Table(name = "plan_attempt")
@Getter
@Setter
public class PlanAttempt {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @ManyToOne(optional = false)
    private io.terrakube.api.rs.job.Job job;
    private int attempt;
    private String status;
    @Column(name = "finished_at")
    private Date finishedAt;
    @Column(name = "retried_by")
    private String retriedBy;
    @Column(columnDefinition = "text")
    private String context;
}
