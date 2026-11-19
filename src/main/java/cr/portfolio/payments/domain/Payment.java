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
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "source_account_id", nullable = false)
    private UUID sourceAccountId;

    @Column(name = "destination_account_id", nullable = false)
    private UUID destinationAccountId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // requerido por JPA
    }

    public static Payment create(UUID sourceAccountId, UUID destinationAccountId, Money money) {
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("La cuenta origen y destino deben ser distintas");
        }
        var payment = new Payment();
        payment.id = UUID.randomUUID();
        payment.sourceAccountId = sourceAccountId;
        payment.destinationAccountId = destinationAccountId;
        payment.amount = money.cents();
        payment.currency = money.currency();
        payment.status = PaymentStatus.PENDING;
        payment.createdAt = Instant.now();
        payment.updatedAt = payment.createdAt;
        return payment;
    }

    public void transitionTo(PaymentStatus next) {
        this.status = status.requireTransitionTo(next);
        this.updatedAt = Instant.now();
    }

    public Money money() { return new Money(amount, currency); }

    public UUID getId() { return id; }
    public UUID getSourceAccountId() { return sourceAccountId; }
    public UUID getDestinationAccountId() { return destinationAccountId; }
    public long getAmount() { return amount; }
    public Currency getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
