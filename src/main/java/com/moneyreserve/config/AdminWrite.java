package com.moneyreserve.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an admin handler that changes configuration: a draft, a discard, a publish or a restore.
 *
 * <p>{@link AdminAccessGuard} refuses these with 403 {@code MR.ADM.E.004} while
 * {@code moneyreserve.insights.admin.config-write-enabled} is off. Read-only POSTs (test, analyse,
 * generate, validate) are deliberately not marked.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AdminWrite {}
