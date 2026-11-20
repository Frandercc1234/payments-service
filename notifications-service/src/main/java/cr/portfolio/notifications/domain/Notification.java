package cr.portfolio.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    private UUID id;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String channel;

    @Column(nullable = false)
    private String message;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
        // requerido por JPA
    }

    public static Notification sent(String messageId, UUID paymentId, String eventType, String channel, String message) {
        var n = new Notification();
        n.id = UUID.randomUUID();
        n.messageId = messageId;
        n.paymentId = paymentId;
        n.eventType = eventType;
        n.channel = channel;
        n.message = message;
        n.status = "SENT";
        n.createdAt = Instant.now();
        return n;
    }

    public UUID getId() { return id; }
    public String getMessageId() { return messageId; }
    public UUID getPaymentId() { return paymentId; }
    public String getEventType() { return eventType; }
    public String getChannel() { return channel; }
    public String getMessage() { return message; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
