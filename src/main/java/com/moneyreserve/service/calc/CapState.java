package com.moneyreserve.service.calc;

/** How a category stands against its cap. Null, rather than a third constant, means "fine". */
public enum CapState {
  APPROACHING,
  EXCEEDED
}
