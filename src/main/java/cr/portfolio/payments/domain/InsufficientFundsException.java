package cr.portfolio.payments.domain;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID accountId) {
        super("Saldo insuficiente en la cuenta " + accountId);
    }
}
