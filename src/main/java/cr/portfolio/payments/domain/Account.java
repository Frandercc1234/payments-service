package cr.portfolio.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    /** Saldo en centavos. La base además lo protege con CHECK (balance >= 0). */
    @Column(nullable = false)
    private long balance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
        // requerido por JPA
    }

    public static Account open(String ownerName, Currency currency, long initialBalance) {
        if (initialBalance < 0) {
            throw new IllegalArgumentException("El saldo inicial no puede ser negativo");
        }
        var account = new Account();
        account.id = UUID.randomUUID();
        account.ownerName = ownerName;
        account.currency = currency;
        account.balance = initialBalance;
        account.createdAt = Instant.now();
        return account;
    }

    public void debit(Money amount) {
        requireSameCurrency(amount);
        if (balance < amount.cents()) {
            throw new InsufficientFundsException(id);
        }
        balance -= amount.cents();
    }

    public void credit(Money amount) {
        requireSameCurrency(amount);
        balance = Math.addExact(balance, amount.cents());
    }

    private void requireSameCurrency(Money amount) {
        if (amount.currency() != currency) {
            throw new CurrencyMismatchException(currency, amount.currency());
        }
    }

    public UUID getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public Currency getCurrency() { return currency; }
    public long getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }
}
