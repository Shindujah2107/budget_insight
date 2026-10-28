package com.moneyreserve.service;

import com.moneyreserve.domain.BiAccount;
import com.moneyreserve.domain.BiCategory;
import com.moneyreserve.domain.BiTransaction;
import com.moneyreserve.domain.TxnDirection;
import com.moneyreserve.domain.TxnSource;
import com.moneyreserve.dto.AccountDto;
import com.moneyreserve.dto.CategoryDto;
import com.moneyreserve.dto.TransactionDto;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.stereotype.Component;

/**
 * Entity to response mapping.
 *
 * <p>Kept in one class so that every amount on the screen is formatted by the same two lines. Money
 * is returned as a decimal string, scaled to two places, never as a JSON number: a client that
 * parses 1234.50 into a double and formats it back can print 1234.5 or 1234.499999, and a currency
 * figure that disagrees with the backend by a cent is a support ticket.
 *
 * <p>Category names resolve English-only in v1. The Sinhala and Tamil columns exist in the schema
 * and are deliberately unpopulated until translations are signed off — returning an untranslated
 * blank would be worse on the screen than returning English.
 *
 * <p>Relations are plain UUIDs rather than lazy {@code @ManyToOne}s, so the account is passed in.
 * That is deliberate: it makes the
 * caller decide how accounts are loaded, and the query service loads a page of them in one go
 * instead of letting a lazy proxy fire once per row.
 */
@Component
public class InsightsMapper {

  /** Two decimal places, always, including trailing zeros. */
  public String money(BigDecimal amount) {
    BigDecimal v = amount == null ? BigDecimal.ZERO : amount;
    return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  /** The signed figure the screen prints, so the client never re-derives the sign. */
  public String displayAmount(BigDecimal amount, TxnDirection direction) {
    String body = money(amount);
    return TxnDirection.CREDIT.equals(direction) ? "+" + body : "-" + body;
  }

  /**
   * @param account the transaction's account, or null for cash and for a caller that does not need
   *     the label
   */
  public TransactionDto toDto(BiTransaction txn, BiAccount account) {
    return TransactionDto.builder()
        .id(txn.getId())
        .merchant(txn.getMerchant())
        .description(txn.getDescription())
        .date(txn.getTxnDate())
        .amount(money(txn.getAmount()))
        .direction(txn.getDirection() == null ? null : txn.getDirection().name())
        .displayAmount(displayAmount(txn.getAmount(), txn.getDirection()))
        .categoryId(txn.getCategoryId())
        .accountId(txn.getAccountId())
        .accountLabel(account == null ? null : label(account))
        .source(txn.getSource() == null ? null : txn.getSource().name())
        .editable(TxnSource.MANUAL.equals(txn.getSource()))
        .isUserCategorised(Boolean.TRUE.equals(txn.getIsUserCategorised()))
        .build();
  }

  public CategoryDto toDto(BiCategory category) {
    return CategoryDto.builder()
        .categoryId(category.getId())
        .code(category.getCode())
        .name(category.getNameEn())
        .iconKey(category.getIconKey())
        .sortOrder(category.getSortOrder() == null ? null : category.getSortOrder().intValue())
        .build();
  }

  public AccountDto toDto(BiAccount account) {
    return AccountDto.builder()
        .accountId(account.getId())
        .bankCode(account.getBankCode())
        .instrumentType(
            account.getInstrumentType() == null ? null : account.getInstrumentType().name())
        .maskedTail(account.getMaskedTail())
        .label(label(account))
        .build();
  }

  /**
   * What the account filter shows.
   *
   * <p>Falls back to the masked tail alone when the bank was never identified, which is the only
   * thing we hold about that instrument. No branch here can produce more than four digits of an
   * instrument, because {@code ck_bi_account_masked_tail} refuses to store anything but three or
   * four digits in the column this reads (V1.14.0). That constraint is what makes the claim true; the column
   * is varchar(8) and, until migration V1.0.3 added the check, the guarantee was documented in
   * several places and enforced in none.
   */
  private String label(BiAccount account) {
    if (account.getDisplayName() != null && !account.getDisplayName().isBlank()) {
      return account.getDisplayName();
    }
    String tail = account.getMaskedTail() == null ? "" : account.getMaskedTail();
    String bank = account.getBankCode();
    return (bank == null || bank.isBlank()) ? tail : (bank + " " + tail).trim();
  }
}
