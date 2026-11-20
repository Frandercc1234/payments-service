package cr.portfolio.payments.service;

import cr.portfolio.payments.domain.Money;
import java.util.UUID;

/** Orden de crear un pago, expresada en términos del servicio (sin depender de la capa web). */
public record CreatePaymentCommand(UUID sourceAccountId, UUID destinationAccountId, Money amount) {
}
