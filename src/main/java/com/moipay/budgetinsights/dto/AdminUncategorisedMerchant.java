package com.moipay.budgetinsights.dto;

/**
 * A merchant that categorisation left in {@code OTHER} (layer 5), for the admin to add keywords.
 *
 * <p>Counts only, over the last 30 days of debits, and only for merchants seen for at least k
 * distinct customers (k ≥ 5), as in the rule impact count. The merchant is the cleaned form ({@link
 * com.moipay.budgetinsights.service.calc.MerchantText}), so bank spellings of one merchant group
 * together.
 *
 * @param merchant the cleaned merchant name
 * @param transactions30d debit transactions in OTHER over the last 30 days
 * @param nowCategory the category the current rules (including unpublished drafts) would give it,
 *     or {@code OTHER} when still none; existing transactions are not re-categorised
 */
public record AdminUncategorisedMerchant(String merchant, long transactions30d, String nowCategory) {}
