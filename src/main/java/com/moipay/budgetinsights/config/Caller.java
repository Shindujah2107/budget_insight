package com.moipay.budgetinsights.config;

import java.util.UUID;

/**
 * The authenticated caller, rebuilt from the gateway's headers.
 *
 * <p>SplitEngine puts a bare userId String in the security principal. Budget Insights needs the
 * tenant as well — every row it writes carries {@code tenant_id NOT NULL} — so the principal is
 * this record instead. The gateway already sends {@code X-Tenant}; core's GatewayAuthFilter reads
 * it too. The shape of the filter is otherwise unchanged.
 *
 * <p>There is no setter and no way to construct one from a request body. The only thing that builds
 * a Caller is {@link GatewayAuthFilter}, from headers the gateway signed off on, which is what makes
 * "the owner is the token holder" structural rather than a convention each endpoint remembers.
 */
public record Caller(UUID userId, UUID tenantId) {}
