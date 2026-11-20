package cr.portfolio.notifications.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Mensaje ya atendido. Su clave primaria (messageId) es lo que descarta los duplicados. */
@Entity
@Table(name = "processed_messages")
public class ProcessedMessage {

    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {
        // requerido por JPA
    }

    public String getMessageId() { return messageId; }
    public Instant getProcessedAt() { return processedAt; }
}
