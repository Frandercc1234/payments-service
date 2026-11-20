package cr.portfolio.payments.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @NotBlank @DefaultValue("payments.events") String exchange,
        @Positive @DefaultValue("50") int batchSize,
        @DefaultValue("5s") Duration confirmTimeout,
        @DefaultValue("500ms") Duration pollInterval) {
}
