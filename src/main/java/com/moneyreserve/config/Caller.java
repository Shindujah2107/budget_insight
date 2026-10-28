package com.moneyreserve.config;

import java.util.UUID;

/**
 * The signed-in user, as the security principal.
 *
 * <p>Built only by {@link JwtAuthFilter}, from a verified access token whose user still exists.
 * There is no way to construct one from a request body, which is what makes "the owner is the token
 * holder" structural: every repository query is scoped by {@link #userId()}.
 */
public record Caller(UUID userId) {}
