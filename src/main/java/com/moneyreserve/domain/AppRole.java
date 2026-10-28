package com.moneyreserve.domain;

/**
 * What a signed-in user may do. Everyone signs in the same way (mobile number + SMS code); the role
 * decides what the access token opens.
 *
 * <p>{@link #SUPER_ADMIN} is the only role the admin API accepts. {@link #ADMIN} is reserved for a
 * narrower staff role and currently opens nothing beyond the customer API. Roles are never chosen at
 * sign-up: they come from configuration ({@code moneyreserve.auth.super-admin-phones}).
 */
public enum AppRole {
  CUSTOMER,
  ADMIN,
  SUPER_ADMIN
}
