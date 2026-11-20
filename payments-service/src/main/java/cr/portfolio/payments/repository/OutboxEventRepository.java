package cr.portfolio.payments.repository;

import cr.portfolio.payments.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Toma el siguiente lote de eventos pendientes y los bloquea. {@code SKIP LOCKED} hace que, si hay
     * dos relays corriendo, cada uno reciba filas distintas en lugar de esperarse o repetirse.
     * Las filas quedan bloqueadas hasta que termina la transacción.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPending(@Param("limit") int limit);
}
