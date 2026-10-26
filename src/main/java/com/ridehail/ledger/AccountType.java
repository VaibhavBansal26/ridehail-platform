package com.ridehail.ledger;

/**
 * Chart of accounts. "Normal side" is the side that increases the balance.
 *
 *   CASH                platform asset      (debit-normal)  money we hold at the payment provider
 *   RIDER_PAYABLE       liability, per rider(credit-normal) earnings we owe a rider
 *   PAYOUTS_IN_TRANSIT  platform liability  (credit-normal) rider money reserved for a payout not yet settled
 *   COMMISSION_REVENUE  platform revenue    (credit-normal) our cut of each fare
 */
public enum AccountType {
    CASH(Direction.DEBIT, true),
    RIDER_PAYABLE(Direction.CREDIT, false),
    PAYOUTS_IN_TRANSIT(Direction.CREDIT, true),
    COMMISSION_REVENUE(Direction.CREDIT, true);

    private final Direction normalSide;
    private final boolean platformAccount;

    AccountType(Direction normalSide, boolean platformAccount) {
        this.normalSide = normalSide;
        this.platformAccount = platformAccount;
    }

    public Direction normalSide() { return normalSide; }

    public boolean isPlatformAccount() { return platformAccount; }
}
