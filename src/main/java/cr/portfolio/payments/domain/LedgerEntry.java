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
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private Direction direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private EntryKind kind;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
        // requerido por JPA
    }

    public static LedgerEntry of(UUID paymentId, UUID accountId, Direction direction, EntryKind kind, Money money) {
        var entry = new LedgerEntry();
        entry.id = UUID.randomUUID();
        entry.paymentId = paymentId;
        entry.accountId = accountId;
        entry.direction = direction;
        entry.kind = kind;
        entry.amount = money.cents();
        entry.currency = money.currency();
        entry.createdAt = Instant.now();
        return entry;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getAccountId() { return accountId; }
    public Direction getDirection() { return direction; }
    public EntryKind getKind() { return kind; }
    public long getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
