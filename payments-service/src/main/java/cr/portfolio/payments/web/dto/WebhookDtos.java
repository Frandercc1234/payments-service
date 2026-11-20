package cr.portfolio.payments.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import cr.portfolio.payments.service.ProviderEventCommand;
import cr.portfolio.payments.service.ProviderEventType;
import cr.portfolio.payments.service.WebhookOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class WebhookDtos {

    private WebhookDtos() {
    }

    /** Formato del evento en el cable: los textos "payment.confirmed" / "payment.failed" son el contrato con el proveedor. */
    public enum EventType {
        @JsonProperty("payment.confirmed") PAYMENT_CONFIRMED(ProviderEventType.PAYMENT_CONFIRMED),
        @JsonProperty("payment.failed") PAYMENT_FAILED(ProviderEventType.PAYMENT_FAILED);

        private final ProviderEventType serviceType;

        EventType(ProviderEventType serviceType) {
            this.serviceType = serviceType;
        }

        ProviderEventType toServiceType() {
            return serviceType;
        }
    }

    public record ProviderEvent(
            @NotBlank @Size(max = 100) String eventId,
            @NotNull EventType type,
            @NotNull UUID paymentId) {

        /** Traduce el evento del cable a la orden que entiende el servicio. */
        public ProviderEventCommand toCommand(String rawPayload) {
            return new ProviderEventCommand(eventId, type.toServiceType(), paymentId, rawPayload);
        }
    }

    public record WebhookAck(String status) {

        public static WebhookAck from(WebhookOutcome outcome) {
            return new WebhookAck(outcome == WebhookOutcome.PROCESSED ? "processed" : "duplicate");
        }
    }
}
