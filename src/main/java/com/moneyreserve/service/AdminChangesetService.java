package com.moneyreserve.service;

import com.moneyreserve.dto.AdminChangeset;
import com.moneyreserve.dto.AdminPage;
import com.moneyreserve.dto.AdminPublishRequest;
import com.moneyreserve.dto.AdminPublishResult;
import com.moneyreserve.dto.AdminRelease;
import com.moneyreserve.dto.AdminRestoreResult;
import com.moneyreserve.dto.AdminValidation;
import java.util.UUID;

/**
 * Admin: Review & publish (screen 23) and Change history (screen 24).
 *
 * <p>Publishing applies the whole shared changeset in one transaction, under a database-wide lock,
 * as a new reference release that handsets pick up on their next sync. Releases are never edited
 * or removed; a mistake is undone by restoring an earlier release, which creates drafts that go
 * through the same review and publish.
 */
public interface AdminChangesetService {

  AdminChangeset changeset();

  /** Drops one pending change; returns the new changeset revision. */
  long discard(UUID changeId, Long ifMatch);

  /** Drops every pending change; returns the new changeset revision. */
  long discardAll(Long ifMatch);

  AdminValidation validate();

  AdminPublishResult publish(AdminPublishRequest request, UUID userId);

  AdminPage<AdminRelease> releases(int page, int size);

  AdminRelease release(int version);

  AdminRestoreResult restore(int version, UUID userId, Long ifMatch);
}
