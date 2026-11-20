package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;

import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.domain.Currency;
import cr.portfolio.payments.repository.AccountRepository;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired AccountRepository accounts;
    @Autowired JdbcTemplate jdbc;

    /** 50 pagos de 1.000 contra una cuenta con saldo para exactamente 10. Se repite para descartar casualidad. */
    @RepeatedTest(5)
    void fiftyParallelPaymentsAgainstBalanceForTenAcceptExactlyTen() throws Exception {
        var from = accounts.save(Account.open("origen", Currency.CRC, 10_000));
        var to = accounts.save(Account.open("destino", Currency.CRC, 0));
        var wentNegative = new AtomicBoolean(false);
        var monitorRunning = new AtomicBoolean(true);

        // Observa el saldo mientras corren los pagos: nunca debe verse negativo, ni siquiera de paso.
        var monitor = new Thread(() -> {
            while (monitorRunning.get()) {
                Long balance = jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, from.getId());
                if (balance != null && balance < 0) {
                    wentNegative.set(true);
                }
            }
        });
        monitor.start();

        var statuses = runInParallel(50, () ->
                postPayment(paymentBody(from, to, 1_000, "CRC"), UUID.randomUUID().toString()).getStatusCode().value());

        monitorRunning.set(false);
        monitor.join();

        long accepted = statuses.stream().filter(s -> s == 201).count();
        long rejected = statuses.stream().filter(s -> s == 422).count();
        assertThat(accepted).as("pagos aceptados").isEqualTo(10);
        assertThat(rejected).as("pagos rechazados por saldo insuficiente").isEqualTo(40);
        assertThat(wentNegative).as("el saldo nunca fue negativo").isFalse();
        assertThat(balanceOf(from)).isZero();
        assertThat(balanceOf(to)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE source_account_id = ?", Integer.class, from.getId()))
                .isEqualTo(10);
        assertThat(ledgerNetForAccountPair(from, to)).isZero();
    }

    /**
     * A→B y B→A al mismo tiempo. Sin bloquear en orden fijo esto produce deadlocks en PostgreSQL
     * (alguna transacción es abortada y el cliente recibe 500).
     */
    @Test
    void opposingPaymentsDoNotDeadlockAndConserveMoney() throws Exception {
        var a = accounts.save(Account.open("A", Currency.CRC, 100_000));
        var b = accounts.save(Account.open("B", Currency.CRC, 100_000));
        var counter = new AtomicLong();

        var statuses = runInParallel(40, () -> {
            boolean forward = counter.getAndIncrement() % 2 == 0;
            var body = forward ? paymentBody(a, b, 100, "CRC") : paymentBody(b, a, 100, "CRC");
            return postPayment(body, UUID.randomUUID().toString()).getStatusCode().value();
        });

        assertThat(statuses).allMatch(s -> s == 201);
        assertThat(balanceOf(a) + balanceOf(b)).isEqualTo(200_000);
        assertThat(ledgerNetForAccountPair(a, b)).isZero();
    }

    /** Lanza N tareas que arrancan todas a la vez (latch) y devuelve sus resultados. */
    private <T> java.util.List<T> runInParallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        var ready = new CountDownLatch(n);
        var go = new CountDownLatch(1);
        var futures = new ArrayList<Future<T>>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.call();
            }));
        }
        ready.await();
        go.countDown();
        var results = new ArrayList<T>();
        for (var f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private long balanceOf(Account a) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, a.getId());
    }

    private long ledgerNetForAccountPair(Account a, Account b) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE direction WHEN 'CREDIT' THEN amount ELSE -amount END), 0)
                FROM ledger_entries WHERE account_id IN (?, ?)""", Long.class, a.getId(), b.getId());
    }
}
