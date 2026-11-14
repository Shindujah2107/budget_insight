package com.moneyreserve.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Where a daily job stopped (V1.24.0): a later run the same day continues from here. */
@Entity
@Table(name = "bi_job_cursor")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BiJobCursor {

  @Id
  @Column(name = "job_name", length = 64)
  private String jobName;

  @Column(name = "run_date", nullable = false)
  private LocalDate runDate;

  /** The last key the run finished (for BillsDailyJob, a customer id); null before the first. */
  @Column(name = "last_key", length = 64)
  private String lastKey;

  @Column(name = "completed", nullable = false)
  private boolean completed;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;
}
