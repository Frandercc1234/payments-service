package cr.portfolio.payments.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class WebhookDtos {

    private WebhookDtos() {
    }

    public enum EventType {
        @JsonProperty("payment.confirmed") PAYMENT_CONFIRMED("payment.confirmed"),
        @JsonProperty("payment.failed") PAYMENT_FAILED("payment.failed");

        private final String wire;

        EventType(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public record ProviderEvent(
            @NotBlank @Size(max = 100) String eventId,
            @NotNull EventType type,
            @NotNull UUID paymentId) {
    }

    public record WebhookAck(String status) {
        public static final WebhookAck PROCESSED = new WebhookAck("processed");
        public static final WebhookAck DUPLICATE = new WebhookAck("duplicate");
    }
}
