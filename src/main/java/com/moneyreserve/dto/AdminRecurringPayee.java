package com.moneyreserve.dto;

import java.util.List;

/**
 * A payee that looks like a bill for several customers but matches no biller. Aggregate only: the
 * normalised name, how many customers, the usual cadence, and keywords to start a biller from.
 */
public record AdminRecurringPayee(String payee, long customers, String cadence, List<String> suggestedTerms) {}
