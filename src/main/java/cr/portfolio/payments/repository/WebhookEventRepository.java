package cr.portfolio.payments.repository;

import cr.portfolio.payments.domain.WebhookEventRecord;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEventRecord, String> {

    /** Registra el evento si es nuevo. Devuelve 1 si lo registró, 0 si ya existía (reenvío). */
    @Modifying
    @Query(value = """
            INSERT INTO webhook_events (event_id, payment_id, event_type, payload, received_at)
            VALUES (:eventId, :paymentId, :type, :payload, now())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int register(@Param("eventId") String eventId, @Param("paymentId") UUID paymentId,
            @Param("type") String type, @Param("payload") String payload);
}
