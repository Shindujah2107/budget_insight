package com.moneyreserve.dto;

/**
 * A signed-in session. {@code accessToken} goes in {@code Authorization: Bearer}; {@code
 * refreshToken} is kept in the device keychain and exchanged for a new pair before the access token
 * runs out ({@code expiresIn} seconds).
 */
public record AuthTokenResponse(
    String tokenType,
    String accessToken,
    long expiresIn,
    String refreshToken,
    long refreshExpiresIn,
    AuthUserDto user) {}
