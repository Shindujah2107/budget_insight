package com.moneyreserve.domain;

/** How a configuration item appears in the admin's effective view. */
public enum ConfigItemState {
  PUBLISHED,
  DRAFT_NEW,
  DRAFT_EDITED,
  DRAFT_DEACTIVATED,
  DRAFT_DELETED,
  DRAFT_RETIRED;

  public static ConfigItemState of(ConfigChangeOp op) {
    if (op == null) {
      return PUBLISHED;
    }
    return switch (op) {
      case CREATE -> DRAFT_NEW;
      case UPDATE, ACTIVATE -> DRAFT_EDITED;
      case DEACTIVATE -> DRAFT_DEACTIVATED;
      case DELETE -> DRAFT_DELETED;
      case RETIRE -> DRAFT_RETIRED;
    };
  }
}
