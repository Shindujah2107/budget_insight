package com.moipay.budgetinsights.service;

import com.moipay.budgetinsights.dto.FormatSampleBatchRequest;
import com.moipay.budgetinsights.dto.FormatSampleBatchResponse;
import com.moipay.budgetinsights.dto.UnmatchedStatsRequest;
import com.moipay.budgetinsights.dto.UnmatchedStatsResponse;
import com.moipay.budgetinsights.config.Caller;
import java.util.UUID;

/** SMS format discovery, customer side: what a handset reports about messages it could not read. */
public interface FormatDiscoveryService {

  /** Phase 1: daily counts per sender. */
  UnmatchedStatsResponse recordStats(Caller caller, UnmatchedStatsRequest request);

  /** Phase 2: message skeletons. */
  FormatSampleBatchResponse recordSamples(Caller caller, FormatSampleBatchRequest request);

  /**
   * Removes every sample this user contributed and recounts the formats they were in. Called on
   * consent withdrawal and erasure. Returns the number of samples removed.
   */
  int forget(UUID userId);
}
