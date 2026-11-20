package cr.portfolio.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import cr.portfolio.notifications.config.RabbitTopology;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.MessagePropertiesBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** El consumidor contra RabbitMQ y PostgreSQL reales: idempotencia, reintentos y cola de mensajes muertos. */
class NotificationConsumerIntegrationTest extends AbstractIntegrationTest {

    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate rest;
    @Value("${app.events.exchange}") String exchange;

    @BeforeEach
    void emptyDeadLetterQueue() {
        admin.purgeQueue(RabbitTopology.DEAD_LETTER_QUEUE, false);
    }

    @Test
    void anEventCreatesOneNotification() {
        var paymentId = UUID.randomUUID();

        publish(UUID.randomUUID().toString(), "payment.created", event("payment.created", paymentId, 2_500, 1));

        awaitNotifications(paymentId, 1);
        var n = notificationsOf(paymentId).get(0);
        assertThat(n.get("status")).isEqualTo("SENT");
        assertThat(n.get("channel")).isEqualTo("LOG");
        assertThat(n.get("eventType")).isEqualTo("payment.created");
        assertThat((String) n.get("message")).contains("CRC 25,00");   // 2500 centavos, formateados sin double
    }

    @Test
    void theSameMessageDeliveredTwiceNotifiesOnce() {
        var paymentId = UUID.randomUUID();
        var messageId = UUID.randomUUID().toString();
        var body = event("payment.created", paymentId, 1_000, 1);

        publish(messageId, "payment.created", body);
        awaitNotifications(paymentId, 1);
        publish(messageId, "payment.created", body);   // el productor garantiza "al menos una vez": llega repetido
        settle();

        assertThat(notificationsOf(paymentId)).hasSize(1);
        assertThat(processedCount(messageId)).isEqualTo(1);
        assertDuplicateWasIgnoredQuietly();
    }

    @Test
    void tenCopiesOfTheSameMessageArrivingTogetherNotifyOnce() {
        var paymentId = UUID.randomUUID();
        var messageId = UUID.randomUUID().toString();
        var body = event("payment.created", paymentId, 1_000, 1);

        for (int i = 0; i < 10; i++) {
            publish(messageId, "payment.created", body);   // 3 consumidores en paralelo se las disputan
        }
        awaitNotifications(paymentId, 1);
        settle();

        assertThat(notificationsOf(paymentId)).hasSize(1);
        assertThat(processedCount(messageId)).isEqualTo(1);
        assertDuplicateWasIgnoredQuietly();
    }

    @Test
    void differentEventsOfTheSamePaymentEachNotify() {
        var paymentId = UUID.randomUUID();

        publish(UUID.randomUUID().toString(), "payment.created", event("payment.created", paymentId, 1_000, 1));
        awaitNotifications(paymentId, 1);
        publish(UUID.randomUUID().toString(), "payment.confirmed", event("payment.confirmed", paymentId, 1_000, 1));
        awaitNotifications(paymentId, 2);

        assertThat(notificationsOf(paymentId)).extracting(n -> n.get("eventType"))
                .containsExactly("payment.created", "payment.confirmed");
    }

    @Test
    void unreadableMessageGoesToTheDeadLetterQueueWithoutNotifying() {
        var messageId = UUID.randomUUID().toString();

        publish(messageId, "payment.created", "esto no es json");

        var dead = rabbit.receive(RabbitTopology.DEAD_LETTER_QUEUE, 10_000);
        assertThat(dead).as("el mensaje venenoso llega a la DLQ").isNotNull();
        assertThat(new String(dead.getBody(), StandardCharsets.UTF_8)).isEqualTo("esto no es json");
        assertThat(processedCount(messageId)).isZero();
    }

    @Test
    void messageWithoutMessageIdGoesToTheDeadLetterQueue() {
        var paymentId = UUID.randomUUID();

        publish(null, "payment.created", event("payment.created", paymentId, 1_000, 1));

        assertThat(rabbit.receive(RabbitTopology.DEAD_LETTER_QUEUE, 10_000)).isNotNull();
        assertThat(notificationsOf(paymentId)).isEmpty();
    }

    @Test
    void unsupportedVersionGoesToTheDeadLetterQueue() {
        var paymentId = UUID.randomUUID();

        publish(UUID.randomUUID().toString(), "payment.created", event("payment.created", paymentId, 1_000, 2));

        assertThat(rabbit.receive(RabbitTopology.DEAD_LETTER_QUEUE, 10_000)).isNotNull();
        assertThat(notificationsOf(paymentId)).isEmpty();
    }

    @Test
    void unknownExtraFieldsAreIgnoredSoTheProducerCanEvolve() {
        var paymentId = UUID.randomUUID();
        var withExtra = event("payment.created", paymentId, 1_000, 1).replace("\"version\":1",
                "\"version\":1,\"campoNuevo\":\"valor\"");

        publish(UUID.randomUUID().toString(), "payment.created", withExtra);

        awaitNotifications(paymentId, 1);
    }

    @Test
    void aFailureWhileNotifyingRollsBackTheClaimAndTheMessageCanBeProcessedLater() {
        var paymentId = UUID.randomUUID();
        var messageId = UUID.randomUUID().toString();
        var body = event("payment.created", paymentId, 1_000, 1);
        var trigger = "fail_notification_" + paymentId.toString().replace("-", "");
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_notification() RETURNS trigger AS $$
                BEGIN
                    IF NEW.payment_id::text = TG_ARGV[0] THEN
                        RAISE EXCEPTION 'base caida (simulado)';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON notifications FOR EACH ROW "
                + "EXECUTE FUNCTION fail_notification('" + paymentId + "')");
        try {
            publish(messageId, "payment.created", body);

            // Tras los reintentos agotados el mensaje va a la DLQ...
            assertThat(rabbit.receive(RabbitTopology.DEAD_LETTER_QUEUE, 10_000)).isNotNull();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON notifications");
        }
        // ...y el mensaje NO quedó marcado como procesado (la reclamación se deshizo junto con la notificación fallida).
        assertThat(processedCount(messageId)).isZero();
        assertThat(notificationsOf(paymentId)).isEmpty();

        // Reentregado cuando la base se recupera, se procesa normalmente.
        publish(messageId, "payment.created", body);
        awaitNotifications(paymentId, 1);
    }

    // ---------- helpers ----------

    private void publish(String messageId, String routingKey, String body) {
        var builder = MessagePropertiesBuilder.newInstance().setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (messageId != null) {
            builder.setMessageId(messageId);
        }
        rabbit.send(exchange, routingKey, new Message(body.getBytes(StandardCharsets.UTF_8), builder.build()));
    }

    private static String event(String type, UUID paymentId, long amount, int version) {
        return """
                {"eventId":"%s","version":%d,"type":"%s","occurredAt":"2026-11-19T12:00:00Z",
                 "payment":{"id":"%s","sourceAccountId":"%s","destinationAccountId":"%s",
                            "amount":%d,"currency":"CRC","status":"PENDING"}}"""
                .formatted(UUID.randomUUID(), version, type, paymentId, UUID.randomUUID(), UUID.randomUUID(), amount);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> notificationsOf(UUID paymentId) {
        return rest.getForObject("/notifications?paymentId=" + paymentId, List.class);
    }

    private void awaitNotifications(UUID paymentId, int expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(
                () -> assertThat(notificationsOf(paymentId)).hasSize(expected));
    }

    private int processedCount(String messageId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_messages WHERE message_id = ?", Integer.class, messageId);
    }

    /**
     * Un duplicado es una situación NORMAL, no un error: se descarta por el chequeo de messageId. Si llegara a
     * fallar por la restricción UNIQUE de la base, el mensaje terminaría reintentándose y en la DLQ.
     */
    private void assertDuplicateWasIgnoredQuietly() {
        assertThat(rabbit.receive(RabbitTopology.DEAD_LETTER_QUEUE, 1_000))
                .as("un duplicado no debe terminar en la DLQ").isNull();
    }

    /** Espera a que la cola se vacíe y a que los consumidores terminen lo que tienen en mano. */
    private void settle() {
        await().atMost(Duration.ofSeconds(10)).until(
                () -> admin.getQueueInfo(RabbitTopology.QUEUE).getMessageCount() == 0);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
