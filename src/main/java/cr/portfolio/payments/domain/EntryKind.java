package cr.portfolio.payments.domain;

/** El ledger es append-only: una reversión es un asiento nuevo, nunca un UPDATE ni un DELETE. */
public enum EntryKind {
    PAYMENT, REVERSAL
}
