package cr.portfolio.payments.service;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String key) {
        super("La Idempotency-Key '" + key + "' ya se usó con un cuerpo distinto");
    }
}
