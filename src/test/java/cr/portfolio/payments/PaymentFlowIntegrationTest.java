package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.repository.AccountRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class PaymentFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired TestRestTemplate rest;
    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createAndFetchAccount() {
        var created = rest.postForEntity("/accounts",
                Map.of("ownerName", "Ana", "currency", "CRC", "initialBalance", 10_000), Map.class);

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).isNotNull();
        var id = created.getBody().get("id");

        var fetched = rest.getForEntity("/accounts/" + id, Map.class);
        assertThat(fetched.getBody()).containsEntry("balance", 10_000).containsEntry("currency", "CRC");
    }

    @Test
    void paymentMovesMoneyAndWritesBalancedLedger() {
        var from = account(Currency.CRC, 10_000);
        var to = account(Currency.CRC, 500);

        var response = pay(from, to, 2_500, "CRC");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "PENDING").containsEntry("amount", 2_500);
        assertThat(balanceOf(from)).isEqualTo(7_500);
        assertThat(balanceOf(to)).isEqualTo(3_000);

        var paymentId = UUID.fromString((String) response.getBody().get("id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE payment_id = ?", Integer.class, paymentId))
                .isEqualTo(2);
        assertThat(ledgerNet(paymentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT amount FROM ledger_entries WHERE payment_id = ? AND direction = 'DEBIT' AND account_id = ?",
                Long.class, paymentId, from.getId())).isEqualTo(2_500L);
    }

    @Test
    void ledgerOfWholeSystemAlwaysSumsToZero() {
        var a = account(Currency.USD, 1_000);
        var b = account(Currency.USD, 1_000);
        pay(a, b, 100, "USD");
        pay(b, a, 30, "USD");

        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries""", Long.class)).isZero();
    }

    @Test
    void insufficientFundsReturns422AndChangesNothing() {
        var from = account(Currency.CRC, 1_000);
        var to = account(Currency.CRC, 0);

        var response = pay(from, to, 1_001, "CRC");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        assertThat(response.getBody()).containsEntry("type", "urn:payments:problem:insufficient-funds");
        assertThat(balanceOf(from)).isEqualTo(1_000);
        assertThat(balanceOf(to)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE source_account_id = ?", Integer.class, from.getId()))
                .isZero();
    }

    @Test
    void currencyMismatchReturns422() {
        var from = account(Currency.CRC, 1_000);
        var to = account(Currency.USD, 0);

        var response = pay(from, to, 100, "CRC");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balanceOf(from)).isEqualTo(1_000);
    }

    @Test
    void unknownAccountReturns404() {
        var from = account(Currency.CRC, 1_000);

        var response = rest.postForEntity("/payments",
                Map.of("sourceAccountId", from.getId(), "destinationAccountId", UUID.randomUUID(),
                        "amount", 10, "currency", "CRC"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceOf(from)).isEqualTo(1_000);
    }

    @Test
    void paymentToSameAccountReturns400() {
        var a = account(Currency.CRC, 1_000);

        assertThat(pay(a, a, 10, "CRC").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nonPositiveAmountReturns400WithFieldErrors() {
        var a = account(Currency.CRC, 1_000);
        var b = account(Currency.CRC, 0);

        var response = pay(a, b, 0, "CRC");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("errors");
    }

    @Test
    void decimalAmountIsRejectedInsteadOfTruncated() {
        var a = account(Currency.CRC, 1_000);
        var b = account(Currency.CRC, 0);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var json = "{\"sourceAccountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amount\":10.5,\"currency\":\"CRC\"}"
                .formatted(a.getId(), b.getId());

        var response = rest.postForEntity("/payments", new HttpEntity<>(json, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(a)).isEqualTo(1_000);
    }

    // --- helpers ---

    private Account account(Currency currency, long balance) {
        return accounts.save(Account.open("test", currency, balance));
    }

    private long balanceOf(Account account) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, account.getId());
    }

    private long ledgerNet(UUID paymentId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE payment_id = ?""", Long.class, paymentId);
    }

    private org.springframework.http.ResponseEntity<Map> pay(Account from, Account to, long amount, String currency) {
        return rest.postForEntity("/payments",
                Map.of("sourceAccountId", from.getId(), "destinationAccountId", to.getId(),
                        "amount", amount, "currency", currency), Map.class);
    }
}
