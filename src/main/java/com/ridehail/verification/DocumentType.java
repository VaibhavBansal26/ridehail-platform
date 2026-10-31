package com.ridehail.verification;

/** What a rider must provide. All four are required. */
public enum DocumentType {
    DRIVING_LICENCE("Driving licence", true, false),
    VEHICLE_REGISTRATION("Vehicle registration", true, false),
    INSURANCE("Insurance certificate", true, false),
    /** A photo of the rider's face, for the reviewer to compare with the licence photo. */
    SELFIE("Selfie", false, true);

    private final String label;
    private final boolean needsExpiry;
    private final boolean imageOnly;

    DocumentType(String label, boolean needsExpiry, boolean imageOnly) {
        this.label = label;
        this.needsExpiry = needsExpiry;
        this.imageOnly = imageOnly;
    }

    public String label() { return label; }
    public boolean needsExpiry() { return needsExpiry; }
    public boolean imageOnly() { return imageOnly; }
}
