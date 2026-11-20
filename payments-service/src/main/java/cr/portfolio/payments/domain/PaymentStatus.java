package cr.portfolio.payments.domain;

/** Máquina de estados del pago. CONFIRMED y FAILED son terminales. */
public enum PaymentStatus {
    PENDING, CONFIRMED, FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return this == PENDING && (next == CONFIRMED || next == FAILED);
    }

    public PaymentStatus requireTransitionTo(PaymentStatus next) {
        if (!canTransitionTo(next)) {
            throw new InvalidStateTransitionException(this, next);
        }
        return next;
    }
}
