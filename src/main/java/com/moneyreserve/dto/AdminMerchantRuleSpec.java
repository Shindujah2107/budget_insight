package com.moneyreserve.dto;

import java.util.List;

/**
 * The Admin's form input for a merchant rule, stored as {@code rule_spec}.
 *
 * @param matchType what the Admin chose: EXACT, PREFIX or REGEX (contains). The stored {@code
 *     match_type} can differ: several PREFIX names are stored as one REGEX.
 * @param names merchant names or phrases, upper-cased
 * @param priorityLevel HIGH, NORMAL or LOW
 */
public record AdminMerchantRuleSpec(String matchType, List<String> names, String priorityLevel) {}
