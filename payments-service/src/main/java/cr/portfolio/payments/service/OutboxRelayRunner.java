package cr.portfolio.payments.service;

import cr.portfolio.payments.config.OutboxProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Ejecuta el relay periódicamente. Solo existe cuando {@code app.outbox.relay.enabled=true} (perfil
 * {@code relay}): la API nunca publica, para que su camino de petición no dependa del broker.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true")
public class OutboxRelayRunner {

    private final OutboxPublisher publisher;
    private final OutboxProperties properties;

    public OutboxRelayRunner(OutboxPublisher publisher, OutboxProperties properties) {
        this.publisher = publisher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval:500ms}")
    public void run() {
        int published;
        do {
            published = publisher.publishPending();
        } while (published >= properties.batchSize());   // lote lleno: probablemente hay más esperando
    }
}
