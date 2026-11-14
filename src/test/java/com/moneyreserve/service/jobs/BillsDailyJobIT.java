package com.moneyreserve.service.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.moneyreserve.config.InsightsProperties;
import com.moneyreserve.domain.BiUserBill;
import com.moneyreserve.domain.BillCadence;
import com.moneyreserve.domain.BillStatus;
import com.moneyreserve.repository.BiJobCursorRepository;
import com.moneyreserve.repository.BiUserBillRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

/** The daily bills check for many customers: pages, skips, a time limit, and resuming (V1.24.0). */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Daily bills check")
class BillsDailyJobIT {

  @Autowired private BillsDailyJob job;
  @Autowired private BiUserBillRepository bills;
  @Autowired private BiJobCursorRepository cursors;
  @Autowired private InsightsProperties properties;

  private final LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Colombo"));

  @AfterEach
  void restore() {
    properties.getBills().setDailyJobPageSize(500);
    properties.getBills().setDailyJobTimeLimit(Duration.ofMinutes(10));
    cursors.deleteAll();
  }

  private UUID customerWithBillDue(LocalDate due) {
    UUID user = UUID.randomUUID();
    UUID id = UUID.randomUUID();
    bills.save(
        BiUserBill.builder()
            .id(id)
            .userId(user)
            .payeeKey("X:" + id)
            .detectedName("House rent")
            .status(BillStatus.MANUAL)
            .cadence(BillCadence.MONTHLY)
            .expectedAmount(new BigDecimal("58000.00"))
            .nextDueOn(due)
            .remindDaysBefore((short) 3)
            .paymentCount((short) 0)
            .createdAt(OffsetDateTime.now())
            .build());
    return user;
  }

  @Test
  @DisplayName("customers with nothing due soon are skipped; the rest come in id order, page by page")
  void pagesAndSkips() {
    UUID soon = customerWithBillDue(today);
    UUID far = customerWithBillDue(today.plusDays(40));
    LocalDate dueBy = today.plusDays(properties.getBills().getDailyJobDueWithinDays());
    Set<UUID> seen = new HashSet<>();
    UUID after = new UUID(0L, 0L);
    for (int guard = 0; guard < 10_000; guard++) {
      List<UUID> page = bills.findDueUserIdsAfter(dueBy, after, PageRequest.of(0, 2));
      if (page.isEmpty()) {
        break;
      }
      for (UUID u : page) {
        assertTrue(seen.add(u), "each customer once");
        after = u;
      }
    }
    assertTrue(seen.contains(soon));
    assertFalse(seen.contains(far));
  }

  @Test
  @DisplayName("a run that hits its time limit pauses, and the next run the same day continues until done")
  void pausesAndResumes() {
    customerWithBillDue(today);
    customerWithBillDue(today.minusDays(2));
    customerWithBillDue(today.plusDays(1));
    cursors.deleteAll();
    properties.getBills().setDailyJobPageSize(1);
    properties.getBills().setDailyJobTimeLimit(Duration.ZERO);

    int runs = 0;
    int checked = 0;
    while (runs < 10_000) {
      int done = job.runNow();
      runs++;
      if (done == 0) {
        break;
      }
      assertEquals(1, done, "one page of one customer per run under a zero time limit");
      checked += done;
      assertFalse(cursors.findById(BillsDailyJob.JOB_NAME).orElseThrow().isCompleted() && done > 0 && runs == 1);
    }
    assertTrue(checked >= 3, "every due customer was checked across the runs: " + checked);
    assertTrue(cursors.findById(BillsDailyJob.JOB_NAME).orElseThrow().isCompleted());
    assertEquals(0, job.runNow(), "the day is done");
  }
}
