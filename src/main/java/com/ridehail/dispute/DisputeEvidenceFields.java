package com.ridehail.dispute;

/** What we tell the provider about a disputed charge. All text; the provider's own field limits are respected by the caps in {@link DisputeEvidenceService}. */
public record DisputeEvidenceFields(String customerName, String productDescription, String serviceDate,
                                    String accessActivityLog, String explanation) {}
