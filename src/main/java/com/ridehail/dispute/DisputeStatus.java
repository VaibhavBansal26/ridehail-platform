package com.ridehail.dispute;

public enum DisputeStatus {
    /** The customer questioned the charge but no money has moved (an early warning or inquiry). */
    INQUIRY,
    /** A real chargeback: the provider has taken the money back while it is decided. Rider earnings are frozen. */
    OPEN,
    /** We won: the money was returned and the freeze lifted. */
    WON,
    /** We lost: the money is gone. */
    LOST,
    /** The inquiry ended without becoming a chargeback. */
    CLOSED;

    public boolean isFinal() {
        return this == WON || this == LOST || this == CLOSED;
    }
}
