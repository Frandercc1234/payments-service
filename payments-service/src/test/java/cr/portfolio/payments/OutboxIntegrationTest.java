package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/** El evento nace en la misma transacción que el cambio de negocio: los dos existen o ninguno. */
class OutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Value("${app.webhook.secret}") String secret;

    @Test
    void creatingAPaymentWritesExactlyOnePendingCreatedEvent() throws Exception {
        var from = account(10_000);
        var to = account(0);

        var response = postPayment(paymentBody(from, to, 2_500, "CRC"), UUID.randomUUID().toString());
        var paymentId = (String) response.getBody().get("id");

        var rows = outboxRowsFor(from);
        assertThat(rows).hasSize(1);
        var row = rows.get(0);
        assertThat(row.get("event_type")).isEqualTo("payment.created");
        assertThat(row.get("aggregate_id").toString()).isEqualTo(paymentId);
        assertThat(row.get("published_at")).as("todavía no publicado").isNull();

        JsonNode payload = json.readTree((String) row.get("payload"));
        assertThat(payload.get("eventId").asText()).isEqualTo(row.get("id").toString());
        assertThat(payload.get("version").asInt()).isEqualTo(1);
        assertThat(payload.get("type").asText()).isEqualTo("payment.created");
        assertThat(payload.get("payment").get("id").asText()).isEqualTo(paymentId);
        assertThat(payload.get("payment").get("amount").asLong()).isEqualTo(2_500);
        assertThat(payload.get("payment").get("currency").asText()).isEqualTo("CRC");
        assertThat(payload.get("payment").get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void aRejectedPaymentLeavesNoEvent() {
        var from = account(100);
        var to = account(0);

        var response = postPayment(paymentBody(from, to, 5_000, "CRC"), UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(outboxRowsFor(from)).isEmpty();
    }

    @Test
    void anIdempotentReplayDoesNotWriteASecondEvent() {
        var from = account(10_000);
        var to = account(0);
        var key = UUID.randomUUID().toString();

        postPayment(paymentBody(from, to, 1_000, "CRC"), key);
        postPayment(paymentBody(from, to, 1_000, "CRC"), key);

        assertThat(outboxRowsFor(from)).hasSize(1);
    }

    @Test
    void ifTheEventCannotBeWrittenThePaymentRollsBackToo() {
        var from = account(10_000);
        var to = account(0);
        var trigger = "fail_outbox_" + from.getId().toString().replace("-", "");
        // Simula una falla del outbox solo para esta cuenta origen: la inserción del evento lanza un error.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION fail_outbox() RETURNS trigger AS $$
                BEGIN
                    IF NEW.payload::jsonb -> 'payment' ->> 'sourceAccountId' = TG_ARGV[0] THEN
                        RAISE EXCEPTION 'outbox caido (simulado)';
                    END IF;
                    RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON outbox_events FOR EACH ROW "
                + "EXECUTE FUNCTION fail_outbox('" + from.getId() + "')");
        try {
            var response = postPayment(paymentBody(from, to, 1_000, "CRC"), UUID.randomUUID().toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + trigger + " ON outbox_events");
        }
        assertThat(balanceOf(from)).isEqualTo(10_000);
        assertThat(balanceOf(to)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE source_account_id = ?",
                Integer.class, from.getId())).isZero();
    }

    @Test
    void webhookConfirmationWritesOneConfirmedEventAndAReplayWritesNone() {
        var from = account(10_000);
        var to = account(0);
        var paymentId = createPayment(from, to, 1_000);
        var body = webhookBody("evt-" + UUID.randomUUID(), "payment.confirmed", paymentId);

        sendWebhook(body);
        sendWebhook(body);   // reenvío

        assertThat(eventTypesFor(from)).containsExactlyInAnyOrder("payment.created", "payment.confirmed");
    }

    @Test
    void webhookFailureWritesAFailedEventWithFailedStatus() throws Exception {
        var from = account(10_000);
        var to = account(0);
        var paymentId = createPayment(from, to, 1_000);

        sendWebhook(webhookBody("evt-" + UUID.randomUUID(), "payment.failed", paymentId));

        assertThat(eventTypesFor(from)).containsExactlyInAnyOrder("payment.created", "payment.failed");
        var failed = outboxRowsFor(from).stream().filter(r -> r.get("event_type").equals("payment.failed")).findFirst().orElseThrow();
        assertThat(json.readTree((String) failed.get("payload")).get("payment").get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void aWebhookThatCannotBeAppliedWritesNoEvent() {
        var from = account(10_000);
        var to = account(0);
        var paymentId = createPayment(from, to, 1_000);
        jdbc.update("UPDATE accounts SET balance = 0 WHERE id = ?", to.getId());   // el destino ya gastó: no se puede revertir

        var response = sendWebhook(webhookBody("evt-" + UUID.randomUUID(), "payment.failed", paymentId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(eventTypesFor(from)).containsExactly("payment.created");
    }

    // ---------- helpers ----------

    private Account account(long balance) {
        return accounts.save(Account.open("test", Currency.CRC, balance));
    }

    private long balanceOf(Account a) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, a.getId());
    }

    private List<java.util.Map<String, Object>> outboxRowsFor(Account from) {
        return jdbc.queryForList("""
                SELECT id, event_type, aggregate_id, payload, published_at FROM outbox_events
                WHERE payload::jsonb -> 'payment' ->> 'sourceAccountId' = ? ORDER BY created_at""", from.getId().toString());
    }

    private List<String> eventTypesFor(Account from) {
        return outboxRowsFor(from).stream().map(r -> (String) r.get("event_type")).toList();
    }

    private UUID createPayment(Account from, Account to, long amount) {
        var response = postPayment(paymentBody(from, to, amount, "CRC"), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString((String) response.getBody().get("id"));
    }

    private String webhookBody(String eventId, String type, UUID paymentId) {
        return "{\"eventId\":\"%s\",\"type\":\"%s\",\"paymentId\":\"%s\"}".formatted(eventId, type, paymentId);
    }

    private org.springframework.http.ResponseEntity<java.util.Map> sendWebhook(String body) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Signature", "sha256=" + hmac(body));
        return rest.exchange("/webhooks/provider", HttpMethod.POST, new HttpEntity<>(body, headers), java.util.Map.class);
    }

    private String hmac(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
