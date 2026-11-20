package cr.portfolio.payments.web.dto;

import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.domain.Payment;
import cr.portfolio.payments.domain.PaymentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** {@code amount} en centavos (long). */
    public record CreatePaymentRequest(
            @NotNull UUID sourceAccountId,
            @NotNull UUID destinationAccountId,
            @Positive long amount,
            @NotNull Currency currency) {
    }

    public record PaymentResponse(
            UUID id,
            UUID sourceAccountId,
            UUID destinationAccountId,
            long amount,
            Currency currency,
            PaymentStatus status,
            Instant createdAt) {

        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getSourceAccountId(), p.getDestinationAccountId(),
                    p.getAmount(), p.getCurrency(), p.getStatus(), p.getCreatedAt());
        }
    }
}
