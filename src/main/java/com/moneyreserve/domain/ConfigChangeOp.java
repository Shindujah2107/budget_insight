package com.moneyreserve.domain;

/** What a pending admin change does to its item when published. */
public enum ConfigChangeOp {
  CREATE,
  UPDATE,
  DEACTIVATE,
  ACTIVATE,
  DELETE,
  RETIRE
}
