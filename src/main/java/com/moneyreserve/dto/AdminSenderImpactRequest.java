package com.moneyreserve.dto;

/** Confirms that turning a sender off may stop templates from receiving SMS. */
public record AdminSenderImpactRequest(Boolean acknowledgeTemplateImpact) {}
