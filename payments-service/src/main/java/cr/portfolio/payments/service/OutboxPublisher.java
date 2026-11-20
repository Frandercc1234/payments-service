package cr.portfolio.payments.service;

import cr.portfolio.payments.config.OutboxProperties;
import cr.portfolio.payments.domain.OutboxEvent;
import cr.portfolio.payments.repository.OutboxEventRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publica a RabbitMQ los eventos pendientes del outbox. Garantía: AL MENOS UNA VEZ.
 *
 * <p>Un evento se marca publicado solo DESPUÉS de que el broker confirma que lo recibió y lo encaminó a una cola.
 * Si el proceso muere entre publicar y confirmar en la base, el evento se publicará otra vez: por eso
 * el consumidor tiene que ser idempotente (descarta duplicados por {@code messageId}).
 */
@Service
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outbox;
    private final RabbitTemplate rabbit;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxEventRepository outbox, RabbitTemplate rabbit, OutboxProperties properties) {
        this.outbox = outbox;
        this.rabbit = rabbit;
        this.properties = properties;
    }

    /**
     * Publica un lote. Devuelve cuántos eventos quedaron publicados. Si uno falla (broker caído, nack, sin
     * cola enlazada) se detiene ahí: ese y los siguientes siguen pendientes y se reintentan en la próxima
     * ronda; los anteriores del lote se confirman igual. Se corta en el primer fallo para no reordenar eventos.
     */
    @Transactional
    public int publishPending() {
        var batch = outbox.lockPending(properties.batchSize());
        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                send(event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("No se pudo publicar el evento {} ({}); queda pendiente: {}",
                        event.getId(), event.getEventType(), e.toString());
                break;
            }
            event.markPublished(Instant.now());
            published++;
        }
        return published;
    }

    private void send(OutboxEvent event) throws InterruptedException, ExecutionException, TimeoutException {
        var messageProperties = MessagePropertiesBuilder.newInstance()
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(event.getId().toString())          // lo que el consumidor usa para deduplicar
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)  // sobrevive a un reinicio del broker
                .setHeader("event_type", event.getEventType())
                .build();
        var message = new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), messageProperties);

        var correlation = new CorrelationData(event.getId().toString());
        rabbit.send(properties.exchange(), event.getEventType(), message, correlation);

        var confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("El broker rechazó el mensaje (nack): " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            // Con mandatory=true el broker devuelve el mensaje si ninguna cola lo recibió; sin esto se perdería en silencio.
            throw new IllegalStateException("Ninguna cola recibió el evento (¿consumidor aún no declarado?)");
        }
    }
}
