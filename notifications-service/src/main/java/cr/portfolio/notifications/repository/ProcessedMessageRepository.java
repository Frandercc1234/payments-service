package cr.portfolio.notifications.repository;

import cr.portfolio.notifications.domain.ProcessedMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {

    /**
     * Reclama el mensaje de forma atómica. Devuelve 1 si es la primera vez, 0 si ya se procesó.
     * Si otra transacción está reclamando el mismo mensaje, PostgreSQL hace esperar a esta hasta que la otra
     * termine: si confirma devuelve 0 (duplicado), si hace rollback la inserción procede.
     */
    @Modifying
    @Query(value = """
            INSERT INTO processed_messages (message_id, processed_at)
            VALUES (:messageId, now())
            ON CONFLICT (message_id) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("messageId") String messageId);
}
