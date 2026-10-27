package com.ridehail.ledger;

/**
 * Chart of accounts. "Normal side" is the side that increases the balance.
 *
 *   CASH                platform asset      (debit-normal)  money we hold at the payment provider
 *   RIDER_PAYABLE       liability, per rider(credit-normal) earnings we owe a rider
 *   PAYOUTS_IN_TRANSIT  platform liability  (credit-normal) rider money reserved for a payout not yet settled
 *   COMMISSION_REVENUE  platform revenue    (credit-normal) our cut of each fare
 *   RIDER_FUNDS_HELD, DISPUTES_IN_PROGRESS, DISPUTE_LOSSES, DISPUTE_FEES: see below; used only by disputes
 */
public enum AccountType {
    CASH(Direction.DEBIT, true),
    RIDER_PAYABLE(Direction.CREDIT, false),
    PAYOUTS_IN_TRANSIT(Direction.CREDIT, true),
    COMMISSION_REVENUE(Direction.CREDIT, true),
    /** Per rider: earnings frozen because of a payment dispute. Still owed to the rider, but cannot be paid out. */
    RIDER_FUNDS_HELD(Direction.CREDIT, false),
    /** Platform asset: money the provider has pulled from our balance for a dispute and may give back if we win. */
    DISPUTES_IN_PROGRESS(Direction.DEBIT, true),
    /** Platform expense: disputes we lost. */
    DISPUTE_LOSSES(Direction.DEBIT, true),
    /** Platform expense: the provider's dispute fees. */
    DISPUTE_FEES(Direction.DEBIT, true);

    private final Direction normalSide;
    private final boolean platformAccount;

    AccountType(Direction normalSide, boolean platformAccount) {
        this.normalSide = normalSide;
        this.platformAccount = platformAccount;
    }

    public Direction normalSide() { return normalSide; }

    public boolean isPlatformAccount() { return platformAccount; }
}
