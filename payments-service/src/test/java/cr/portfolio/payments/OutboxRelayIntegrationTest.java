package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.portfolio.payments.config.OutboxProperties;
import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.repository.AccountRepository;
import cr.portfolio.payments.service.OutboxPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** El relay contra un RabbitMQ real: publica, respeta el orden, no pierde nada y no duplica entre relays. */
@TestPropertySource(properties = "app.outbox.batch-size=5")
class OutboxRelayIntegrationTest extends AbstractRabbitIntegrationTest {

    @Autowired OutboxPublisher publisher;
    @Autowired OutboxProperties properties;
    @Autowired AmqpAdmin admin;
    @Autowired RabbitTemplate rabbit;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper json;

    private String queue;

    @BeforeEach
    void isolate() {
        // Otras clases de prueba dejan eventos pendientes en la misma base: se marcan publicados para partir de cero.
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE published_at IS NULL");
        queue = null;
    }

    @AfterEach
    void cleanUp() {
        if (queue != null) {
            admin.deleteQueue(queue);
        }
    }

    @Test
    void publishesAPaymentCreatedEventAndMarksItPublished() throws Exception {
        bindQueue();
        var from = accounts.save(Account.open("origen", Currency.CRC, 10_000));
        var to = accounts.save(Account.open("destino", Currency.CRC, 0));
        var response = postPayment(paymentBody(from, to, 2_500, "CRC"), UUID.randomUUID().toString());
        var paymentId = (String) response.getBody().get("id");

        int published = drain();

        assertThat(published).isEqualTo(1);
        var message = rabbit.receive(queue, 5_000);
        assertThat(message).isNotNull();
        var props = message.getMessageProperties();
        var body = json.readTree(new String(message.getBody()));
        assertThat(props.getReceivedRoutingKey()).isEqualTo("payment.created");
        assertThat(props.getMessageId()).isEqualTo(body.get("eventId").asText());
        assertThat(props.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(body.get("payment").get("id").asText()).isEqualTo(paymentId);
        assertThat(body.get("payment").get("amount").asLong()).isEqualTo(2_500);
        assertThat(pendingCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?::uuid",
                Boolean.class, props.getMessageId())).isTrue();
    }

    @Test
    void publishesInCreationOrder() {
        bindQueue();
        var ids = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            ids.add(insertPendingEvent(Instant.now().plusMillis(i * 10L)).toString());
        }

        drain();

        var received = new ArrayList<String>();
        for (int i = 0; i < 12; i++) {
            received.add(rabbit.receive(queue, 5_000).getMessageProperties().getMessageId());
        }
        assertThat(received).containsExactlyElementsOf(ids);
    }

    @Test
    void withNoQueueBoundTheEventStaysPendingAndIsDeliveredOnceAConsumerAppears() {
        var id = insertPendingEvent(Instant.now());

        int whileNobodyListens = publisher.publishPending();
        assertThat(whileNobodyListens).as("el broker lo devuelve (mandatory): no se descarta en silencio").isZero();
        assertThat(pendingCount()).isEqualTo(1);

        bindQueue();   // aparece el consumidor y declara su cola
        drain();

        assertThat(pendingCount()).isZero();
        assertThat(rabbit.receive(queue, 5_000).getMessageProperties().getMessageId()).isEqualTo(id.toString());
    }

    @Test
    void twoRelaysAtTheSameTimePublishEveryEventExactlyOnce() throws Exception {
        bindQueue();
        int total = 40;
        for (int i = 0; i < total; i++) {
            insertPendingEvent(Instant.now().plusMillis(i));
        }
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
        for (int relay = 0; relay < 2; relay++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return drain();
            }));
        }
        ready.await();
        go.countDown();
        int publishedByRelays = 0;
        for (var f : futures) {
            publishedByRelays += f.get();
        }
        pool.shutdown();

        assertThat(publishedByRelays).isEqualTo(total);
        var seen = new HashSet<String>();
        var duplicates = new ArrayList<String>();
        for (int i = 0; i < total; i++) {
            var message = rabbit.receive(queue, 5_000);
            assertThat(message).as("mensaje %d de %d", i + 1, total).isNotNull();
            if (!seen.add(message.getMessageProperties().getMessageId())) {
                duplicates.add(message.getMessageProperties().getMessageId());
            }
        }
        assertThat(duplicates).isEmpty();
        assertThat(rabbit.receive(queue, 500)).as("no sobra ningún mensaje").isNull();
        assertThat(pendingCount()).isZero();
    }

    // ---------- helpers ----------

    /** Declara una cola temporal enlazada al exchange de eventos, como haría un consumidor. */
    private void bindQueue() {
        queue = "test-" + UUID.randomUUID();
        var exchange = new TopicExchange(properties.exchange(), true, false);
        // Sin autoDelete: receive(queue, timeout) usa un consumidor, y RabbitMQ borraría la cola al cancelarlo.
        var q = new Queue(queue, false, false, false);
        admin.declareExchange(exchange);
        admin.declareQueue(q);
        admin.declareBinding(BindingBuilder.bind(q).to(exchange).with("payment.#"));
    }

    /** Llama al relay hasta que no queda nada publicable; devuelve cuántos publicó. */
    private int drain() {
        int total = 0;
        int n;
        do {
            n = publisher.publishPending();
            total += n;
        } while (n > 0);
        return total;
    }

    private int pendingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
    }

    private UUID insertPendingEvent(Instant createdAt) {
        var id = UUID.randomUUID();
        var payload = "{\"eventId\":\"%s\",\"version\":1,\"type\":\"payment.created\"}".formatted(id);
        jdbc.update("INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at) "
                + "VALUES (?, 'payment', ?, 'payment.created', ?, ?)", id, UUID.randomUUID(), payload, Timestamp.from(createdAt));
        return id;
    }
}
