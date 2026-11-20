package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.repository.AccountRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class WebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;
    @Value("${app.webhook.secret}") String secret;

    // ---------- firma ----------

    @Test
    void validSignatureConfirmsPaymentAndRecordsEvent() {
        var p = newPayment(10_000, 2_500);

        var response = sendEvent(event("evt-" + UUID.randomUUID(), "payment.confirmed", p.id()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "processed");
        assertThat(statusOf(p.id())).isEqualTo("CONFIRMED");
        assertThat(eventCount(p.id())).isEqualTo(1);
        assertThat(balanceOf(p.from())).isEqualTo(7_500);   // confirmar no mueve dinero otra vez
    }

    @Test
    void invalidSignatureIsRejectedAndChangesNothing() {
        var p = newPayment(10_000, 2_500);
        var body = event("evt-" + UUID.randomUUID(), "payment.confirmed", p.id());

        var response = send(body, "sha256=" + "0".repeat(64));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("type", "urn:payments:problem:invalid-signature");
        assertThat(statusOf(p.id())).isEqualTo("PENDING");
        assertThat(eventCount(p.id())).isZero();
    }

    @Test
    void missingSignatureIsRejected() {
        var p = newPayment(10_000, 2_500);

        var response = send(event("evt-" + UUID.randomUUID(), "payment.confirmed", p.id()), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(p.id())).isEqualTo("PENDING");
    }

    @Test
    void malformedSignatureIsRejected() {
        var p = newPayment(10_000, 2_500);
        var body = event("evt-" + UUID.randomUUID(), "payment.confirmed", p.id());

        assertThat(send(body, "sha256=zzzz").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(body, hmac(body)).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);   // sin prefijo "sha256="
    }

    @Test
    void tamperedBodyInvalidatesTheSignature() {
        var p = newPayment(10_000, 2_500);
        var signedBody = event("evt-" + UUID.randomUUID(), "payment.failed", p.id());
        var signature = "sha256=" + hmac(signedBody);
        var tampered = signedBody.replace("payment.failed", "payment.confirmed");

        var response = send(tampered, signature);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(statusOf(p.id())).isEqualTo("PENDING");
    }

    @Test
    void signatureFromAnotherSecretIsRejected() {
        var p = newPayment(10_000, 2_500);
        var body = event("evt-" + UUID.randomUUID(), "payment.confirmed", p.id());

        var response = send(body, "sha256=" + hmacWith("otro-secreto", body));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------- reenvío (idempotencia del webhook) ----------

    @Test
    void replayedEventHasNoEffect() {
        var p = newPayment(10_000, 2_500);
        var body = event("evt-" + UUID.randomUUID(), "payment.failed", p.id());

        var first = sendEvent(body);
        var replay = sendEvent(body);

        assertThat(first.getBody()).containsEntry("status", "processed");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("status", "duplicate");
        assertThat(balanceOf(p.from())).isEqualTo(10_000);     // revertido UNA sola vez
        assertThat(balanceOf(p.to())).isZero();
        assertThat(ledgerEntries(p.id())).isEqualTo(4);        // 2 del pago + 2 de la reversión, no 6
        assertThat(eventCount(p.id())).isEqualTo(1);
    }

    @Test
    void tenSimultaneousDeliveriesOfTheSameEventApplyOnce() throws Exception {
        var p = newPayment(10_000, 2_500);
        var body = event("evt-" + UUID.randomUUID(), "payment.failed", p.id());
        int n = 10;
        var ready = new CountDownLatch(n);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(n);
        var futures = new ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return (String) sendEvent(body).getBody().get("status");
            }));
        }
        ready.await();
        go.countDown();
        var statuses = new ArrayList<String>();
        for (var f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn("processed"::equals).hasSize(1);
        assertThat(statuses).filteredOn("duplicate"::equals).hasSize(n - 1);
        assertThat(balanceOf(p.from())).isEqualTo(10_000);
        assertThat(ledgerEntries(p.id())).isEqualTo(4);
    }

    // ---------- efectos y transiciones ----------

    @Test
    void failedEventReversesMoneyWithCompensatingEntriesAndKeepsLedgerBalanced() {
        var p = newPayment(10_000, 2_500);

        var response = sendEvent(event("evt-" + UUID.randomUUID(), "payment.failed", p.id()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(p.id())).isEqualTo("FAILED");
        assertThat(balanceOf(p.from())).isEqualTo(10_000);
        assertThat(balanceOf(p.to())).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE payment_id = ?""", Long.class, p.id())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ? AND kind = 'REVERSAL'", Integer.class, p.id()))
                .isEqualTo(2);
    }

    @Test
    void differentEventAfterTerminalStateReturns409() {
        var p = newPayment(10_000, 2_500);
        sendEvent(event("evt-" + UUID.randomUUID(), "payment.failed", p.id()));

        var late = sendEvent(event("evt-" + UUID.randomUUID(), "payment.confirmed", p.id()));

        assertThat(late.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(late.getBody()).containsEntry("type", "urn:payments:problem:invalid-state-transition");
        assertThat(statusOf(p.id())).isEqualTo("FAILED");
        assertThat(balanceOf(p.from())).isEqualTo(10_000);
    }

    @Test
    void failureCannotBeReversedIfDestinationAlreadySpentTheMoney() {
        var p = newPayment(10_000, 2_500);
        jdbc.update("UPDATE accounts SET balance = 0 WHERE id = ?", p.to().getId());   // el destino ya gastó

        var response = sendEvent(event("evt-" + UUID.randomUUID(), "payment.failed", p.id()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(statusOf(p.id())).isEqualTo("PENDING");     // nada quedó a medias
        assertThat(eventCount(p.id())).isZero();               // el evento no se registró: el proveedor puede reintentar
        assertThat(balanceOf(p.from())).isEqualTo(7_500);
    }

    @Test
    void unknownPaymentReturns404() {
        var response = sendEvent(event("evt-" + UUID.randomUUID(), "payment.confirmed", UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void validlySignedButMalformedBodyReturns400() {
        var body = "{\"eventId\":\"x\",\"type\":\"payment.exploded\",\"paymentId\":\"no-es-uuid\"}";

        assertThat(sendEvent(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(sendEvent("esto no es json").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---------- helpers ----------

    private record Created(UUID id, Account from, Account to) {
    }

    private Created newPayment(long fromBalance, long amount) {
        var from = accounts.save(Account.open("origen", Currency.CRC, fromBalance));
        var to = accounts.save(Account.open("destino", Currency.CRC, 0));
        var response = postPayment(paymentBody(from, to, amount, "CRC"), UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new Created(UUID.fromString((String) response.getBody().get("id")), from, to);
    }

    private String event(String eventId, String type, UUID paymentId) {
        return "{\"eventId\":\"%s\",\"type\":\"%s\",\"paymentId\":\"%s\"}".formatted(eventId, type, paymentId);
    }

    private ResponseEntity<Map> sendEvent(String body) {
        return send(body, "sha256=" + hmac(body));
    }

    private ResponseEntity<Map> send(String body, String signature) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (signature != null) {
            headers.set("X-Signature", signature);
        }
        return rest.exchange("/webhooks/provider", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private String hmac(String body) {
        return hmacWith(secret, body);
    }

    private static String hmacWith(String key, String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String statusOf(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class, paymentId);
    }

    private long balanceOf(Account a) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, a.getId());
    }

    private int eventCount(UUID paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM webhook_events WHERE payment_id = ?", Integer.class, paymentId);
    }

    private int ledgerEntries(UUID paymentId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId);
    }
}
