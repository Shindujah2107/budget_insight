package com.moipay.budgetinsights.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An admin read with its own per-Super-Admin rate limit (architecture doc §3.1): merchant-rule
 * tests 30 / min, template builder calls 120 / min. Enforced by {@link AdminAccessGuard}; over the
 * limit → 429 {@code MOI.BI.ADM.E.022}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminRateLimit {

  Bucket value();

  enum Bucket {
    RULE_TEST,
    BUILDER
  }
}
