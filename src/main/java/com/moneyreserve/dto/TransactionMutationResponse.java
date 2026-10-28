package com.moneyreserve.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What comes back from adding, correcting or removing a transaction.
 *
 * <p>The recomputed breakdown is returned with the write, in the same response, rather than leaving
 * the client to issue a second call. Two calls means a window in which the screen shows a
 * transaction in its new category above a total that still reflects the old one, and it doubles the
 * request count on the interaction customers perform most. One call also makes the guarantee
 * simple: what you are looking at is what the backend computed from the write you just made.
 *
 * <p>{@code deleted} marks a soft-deleted row. The transaction is still described so the client can
 * animate it away and offer an undo without holding its own copy.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransactionMutationResponse {

  private TransactionDto transaction;

  /** The breakdown for the period the client is looking at, recomputed after the write. */
  private SpendingBreakdownResponse breakdown;

  /** True when this response reports an existing row rather than one created by this call. */
  private Boolean duplicate;

  private Boolean deleted;

  /** The category the transaction moved out of, when this call changed it. */
  private UUID previousCategoryId;
}
