package cr.portfolio.payments.service;

public enum WebhookOutcome {
    /** El evento era nuevo y se aplicó. */
    PROCESSED,
    /** El evento ya se había procesado (reenvío): no se hizo nada. */
    DUPLICATE
}
