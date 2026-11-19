package cr.portfolio.payments.domain;

public class InvalidStateTransitionException extends RuntimeException {
    public InvalidStateTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Transición inválida: " + from + " -> " + to);
    }
}
