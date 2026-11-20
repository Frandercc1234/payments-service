package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.repository.AccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sameKeyAndSameBodyReturnsSameResultAndChargesOnce() {
        var from = account(10_000);
        var to = account(0);
        var key = UUID.randomUUID().toString();

        var first = postPayment(paymentBody(from, to, 1_000, "CRC"), key);
        var second = postPayment(paymentBody(from, to, 1_000, "CRC"), key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(first.getHeaders().containsKey("Idempotent-Replayed")).isFalse();
        assertThat(second.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(balanceOf(from)).isEqualTo(9_000);
        assertThat(balanceOf(to)).isEqualTo(1_000);
        assertThat(paymentCount(from)).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyReturns409AndDoesNotCharge() {
        var from = account(10_000);
        var to = account(0);
        var key = UUID.randomUUID().toString();
        postPayment(paymentBody(from, to, 1_000, "CRC"), key);

        var conflicting = postPayment(paymentBody(from, to, 2_000, "CRC"), key);

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflicting.getBody()).containsEntry("type", "urn:payments:problem:idempotency-key-reused");
        assertThat(balanceOf(from)).isEqualTo(9_000);
        assertThat(paymentCount(from)).isEqualTo(1);
    }

    @Test
    void jsonFieldOrderDoesNotChangeTheRequestIdentity() {
        var from = account(10_000);
        var to = account(0);
        var key = UUID.randomUUID().toString();
        var a = "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":500,\"currency\":\"CRC\"}"
                .formatted(from.getId(), to.getId());
        var b = "{ \"currency\":\"CRC\", \"amount\":500, \"destinationAccountId\":\"%s\", \"sourceAccountId\":\"%s\" }"
                .formatted(to.getId(), from.getId());

        var first = postPayment(a, key);
        var second = postPayment(b, key);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(paymentCount(from)).isEqualTo(1);
    }

    @Test
    void differentKeysCreateDifferentPayments() {
        var from = account(10_000);
        var to = account(0);

        postPayment(paymentBody(from, to, 100, "CRC"), UUID.randomUUID().toString());
        postPayment(paymentBody(from, to, 100, "CRC"), UUID.randomUUID().toString());

        assertThat(paymentCount(from)).isEqualTo(2);
        assertThat(balanceOf(from)).isEqualTo(9_800);
    }

    @Test
    void missingKeyReturns400() {
        var from = account(10_000);
        var to = account(0);

        var response = postPayment(paymentBody(from, to, 100, "CRC"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(from)).isEqualTo(10_000);
    }

    @Test
    void failedAttemptDoesNotConsumeTheKey() {
        var from = account(100);
        var to = account(0);
        var key = UUID.randomUUID().toString();

        var failed = postPayment(paymentBody(from, to, 500, "CRC"), key);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        jdbc.update("UPDATE accounts SET balance = 1000 WHERE id = ?", from.getId());
        var retried = postPayment(paymentBody(from, to, 500, "CRC"), key);

        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balanceOf(from)).isEqualTo(500);
    }

    @Test
    void tenSimultaneousRequestsWithTheSameKeyChargeExactlyOnce() throws Exception {
        var from = account(10_000);
        var to = account(0);
        var key = UUID.randomUUID().toString();
        int threads = 10;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);

        var futures = new ArrayList<java.util.concurrent.Future<Object>>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                var r = postPayment(paymentBody(from, to, 1_000, "CRC"), key);
                return List.of(r.getStatusCode().value(), r.getBody().get("id"));
            }));
        }
        ready.await();
        go.countDown();

        var ids = new java.util.HashSet<Object>();
        for (var f : futures) {
            var result = (List<?>) f.get();
            assertThat(result.get(0)).isEqualTo(201);
            ids.add(result.get(1));
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(paymentCount(from)).isEqualTo(1);
        assertThat(balanceOf(from)).isEqualTo(9_000);
        assertThat(balanceOf(to)).isEqualTo(1_000);
    }

    private Account account(long balance) {
        return accounts.save(Account.open("test", Currency.CRC, balance));
    }

    private long balanceOf(Account a) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, a.getId());
    }

    private int paymentCount(Account from) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE source_account_id = ?", Integer.class, from.getId());
    }
}
