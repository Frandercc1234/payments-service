package cr.portfolio.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Evento del proveedor ya procesado. Su clave primaria (eventId) es lo que vuelve inofensivo un reenvío. */
@Entity
@Table(name = "webhook_events")
public class WebhookEventRecord {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String payload;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WebhookEventRecord() {
        // requerido por JPA
    }

    public String getEventId() { return eventId; }
    public UUID getPaymentId() { return paymentId; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public Instant getReceivedAt() { return receivedAt; }
}
