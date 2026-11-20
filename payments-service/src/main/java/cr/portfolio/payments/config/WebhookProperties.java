package cr.portfolio.payments.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Se llena desde {@code app.webhook.*} en application.yml o desde variables de entorno (WEBHOOK_SECRET). */
@Validated
@ConfigurationProperties(prefix = "app.webhook")
public record WebhookProperties(@NotBlank String secret) {
}
