package cr.portfolio.payments.service;

import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.domain.Payment;
import cr.portfolio.payments.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Foto inmutable de un pago tal como se creó. Es lo que {@link IdempotentPaymentService} guarda y repite
 * ante una clave repetida. Los nombres de campo coinciden con el JSON ya guardado, así que las claves
 * existentes siguen siendo legibles.
 */
public record PaymentSnapshot(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        long amount,
        Currency currency,
        PaymentStatus status,
        Instant createdAt) {

    public static PaymentSnapshot from(Payment p) {
        return new PaymentSnapshot(p.getId(), p.getSourceAccountId(), p.getDestinationAccountId(),
                p.getAmount(), p.getCurrency(), p.getStatus(), p.getCreatedAt());
    }
}
