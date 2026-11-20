package cr.portfolio.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Si el contexto arranca, Flyway aplicó las migraciones y Hibernate (ddl-auto=validate)
 * confirmó que las entidades coinciden con las tablas.
 */
class SchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void databaseRejectsNegativeBalance() {
        assertThatThrownBy(() -> insertAccount(-1, "CRC"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownCurrency() {
        assertThatThrownBy(() -> insertAccount(0, "EUR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseAcceptsValidAccount() {
        insertAccount(5_000, "USD");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE balance = 5000", Integer.class))
                .isGreaterThanOrEqualTo(1);
    }

    private void insertAccount(long balance, String currency) {
        jdbc.update("INSERT INTO accounts (id, owner_name, currency, balance, created_at) VALUES (?, 'x', ?, ?, now())",
                UUID.randomUUID(), currency, balance);
    }
}
