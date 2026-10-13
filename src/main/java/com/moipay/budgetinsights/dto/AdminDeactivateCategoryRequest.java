package com.moipay.budgetinsights.dto;

import java.util.UUID;

/**
 * Deactivate a category.
 *
 * @param moveMerchantRulesTo the category its merchant rules move to; {@code null} turns those
 *     rules off with the category
 */
public record AdminDeactivateCategoryRequest(UUID moveMerchantRulesTo) {}
