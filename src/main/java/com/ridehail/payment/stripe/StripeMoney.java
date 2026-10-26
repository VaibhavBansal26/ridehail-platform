package com.ridehail.payment.stripe;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/** Stripe wants integer amounts in the currency's smallest unit (cents, but whole yen). */
final class StripeMoney {
    private StripeMoney() {}

    static long toMinorUnits(BigDecimal amount, String currencyCode) {
        int digits = Math.max(Currency.getInstance(currencyCode.toUpperCase()).getDefaultFractionDigits(), 0);
        // UNNECESSARY: refuse to silently round money; a fractional minor unit is a bug upstream
        return amount.movePointRight(digits).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
    }
}
