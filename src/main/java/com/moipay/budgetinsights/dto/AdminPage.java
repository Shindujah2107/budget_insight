package com.moipay.budgetinsights.dto;

import java.util.List;

/** A page of results. */
public record AdminPage<T>(List<T> items, int page, int size, long totalElements, int totalPages) {}
