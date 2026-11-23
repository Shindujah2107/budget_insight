package com.moneyreserve.service;

import com.moneyreserve.config.Caller;
import com.moneyreserve.dto.AuthCodeSentResponse;
import com.moneyreserve.dto.AuthLoginRequest;
import com.moneyreserve.dto.AuthProfileUpdateRequest;
import com.moneyreserve.dto.AuthRegisterRequest;
import com.moneyreserve.dto.AuthTokenResponse;
import com.moneyreserve.dto.AuthUserDto;
import com.moneyreserve.dto.AuthVerifyRequest;
import com.moneyreserve.dto.DataErasureResponse;

/**
 * App sign-in: a mobile number proves who the customer is by receiving an SMS code; this service
 * then issues a short-lived access token (JWT) and a rotating refresh token. There are no passwords.
 */
public interface AuthService {

  /** Starts sign-up: records the pending account and texts a REGISTER code. */
  AuthCodeSentResponse register(AuthRegisterRequest request);

  /** As {@link #register(AuthRegisterRequest)}, counting the request against the caller's address. */
  AuthCodeSentResponse register(AuthRegisterRequest request, String clientIp);

  /**
   * Texts a LOGIN code, or a REGISTER code when the number started sign-up but never finished it.
   */
  AuthCodeSentResponse login(AuthLoginRequest request);

  /** As {@link #login(AuthLoginRequest)}, counting the request against the caller's address. */
  AuthCodeSentResponse login(AuthLoginRequest request, String clientIp);

  /** Checks the code and signs the customer in. */
  AuthTokenResponse verify(AuthVerifyRequest request);

  /** Exchanges a refresh token for a new pair; the one presented stops working. */
  AuthTokenResponse refresh(String refreshToken);

  /** Ends the session the refresh token belongs to. Unknown tokens are ignored. */
  void logout(String refreshToken);

  AuthUserDto me(Caller caller);

  /** Changes the customer's name and email. The phone number is the login and stays as it is. */
  AuthUserDto updateProfile(Caller caller, AuthProfileUpdateRequest request);

  /**
   * Deletes the customer: erases all their Budget Insights data (the same erasure as {@code DELETE
   * /api/insights/data}), ends every session and removes the sign-in account, in one transaction.
   */
  DataErasureResponse deleteAccount(Caller caller);
}
