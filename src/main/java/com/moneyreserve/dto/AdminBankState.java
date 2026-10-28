package com.moneyreserve.dto;

/** A bank's editable values (a published row or a draft's desired state). The code never changes. */
public record AdminBankState(String bankCode, String bankName, boolean active) {}
