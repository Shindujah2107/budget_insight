package com.moipay.budgetinsights.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.moipay.budgetinsights.domain.BiReferenceRelease;
import com.moipay.budgetinsights.domain.ConfigChangeOp;
import com.moipay.budgetinsights.domain.ConfigEntityType;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyInput;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyState;
import com.moipay.budgetinsights.dto.AdminBudgetPolicyView;
import com.moipay.budgetinsights.dto.AdminWriteResult;
import com.moipay.budgetinsights.exception.AdminFieldError;
import com.moipay.budgetinsights.exception.BudgetInsightsException;
import com.moipay.budgetinsights.exception.ErrorCode;
import com.moipay.budgetinsights.repository.BiConfigChangeRepository;
import com.moipay.budgetinsights.repository.BiReferenceReleaseRepository;
import com.moipay.budgetinsights.service.AdminBudgetPolicyService;
import com.moipay.budgetinsights.service.impl.AdminEffectiveView.Item;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminBudgetPolicyServiceImpl implements AdminBudgetPolicyService {

  /** How far back the "last published by" lookup reads release history. */
  private static final int HISTORY_SCAN = 200;

  private final AdminEffectiveView view;
  private final AdminDrafts drafts;
  private final AdminReleaseVersion releaseVersion;
  private final BiReferenceReleaseRepository releases;
  private final BiConfigChangeRepository changes;

  @Override
  @Transactional(readOnly = true)
  public AdminBudgetPolicyView get() {
    return toView(drafts.revision());
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminBudgetPolicyView> update(
      AdminBudgetPolicyInput input, UUID userId, Long ifMatch) {
    drafts.begin(ifMatch);
    AdminBudgetPolicyState desired = input.toState();
    List<String[]> problems = desired.problems();
    if (!problems.isEmpty()) {
      String[] first = problems.get(0);
      log.info("bi-admin budget policy refused by={} field={} problems={}", userId, first[0], problems.size());
      throw BudgetInsightsException.withDetails(
          ErrorCode.ADMIN_FIELD_INVALID,
          "Budget policy: " + AdminBudgetPolicyState.describe(first) + ".",
          AdminFieldError.of(first[0], first[1]));
    }
    Item<AdminBudgetPolicyState> item = item();
    drafts.draft(
        ConfigEntityType.BUDGET_POLICY,
        AdminEffectiveView.BUDGET_POLICY_ID,
        ConfigChangeOp.UPDATE,
        item.published(),
        desired,
        userId);
    long revision = drafts.commit();
    log.info("bi-admin budget policy draft saved by={} values={} revision={}", userId, desired, revision);
    return new AdminWriteResult<>(toView(revision), revision);
  }

  @Override
  @Transactional
  public AdminWriteResult<AdminBudgetPolicyView> discard(Long ifMatch) {
    drafts.begin(ifMatch);
    changes
        .findByEntityTypeAndTargetId(ConfigEntityType.BUDGET_POLICY, AdminEffectiveView.BUDGET_POLICY_ID)
        .ifPresent(
            c -> {
              changes.delete(c);
              log.info("bi-admin budget policy draft discarded change={}", c.getId());
            });
    long revision = drafts.commit();
    return new AdminWriteResult<>(toView(revision), revision);
  }

  private Item<AdminBudgetPolicyState> item() {
    return view.budgetPolicy().get(0);
  }

  private AdminBudgetPolicyView toView(long revision) {
    Item<AdminBudgetPolicyState> item = item();
    return new AdminBudgetPolicyView(
        item.id(),
        item.state(),
        item.changeId(),
        item.published(),
        item.effective(),
        lastPublished(),
        revision,
        releaseVersion.current());
  }

  /** The newest release whose diff touched the policy (configuration only, no customer data). */
  private AdminBudgetPolicyView.LastPublished lastPublished() {
    for (BiReferenceRelease r :
        releases.findAllByOrderByVersionDesc(PageRequest.of(0, HISTORY_SCAN)).getContent()) {
      if (r.getDiff() != null && r.getDiff().contains("\"" + ConfigEntityType.BUDGET_POLICY.name() + "\"")) {
        return new AdminBudgetPolicyView.LastPublished(
            r.getVersion(), r.getPublishedAt(), r.getPublishedBy(), r.getReason());
      }
    }
    return null;
  }
}
