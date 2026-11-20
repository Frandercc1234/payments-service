package cr.portfolio.payments.repository;

import cr.portfolio.payments.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, String> {

    /**
     * Reclama la clave de forma atómica. Devuelve 1 si la reclamamos nosotros, 0 si ya existía.
     *
     * <p>Si otra transacción está insertando la misma clave, PostgreSQL hace esperar a esta hasta que
     * la otra termine: si confirma devuelve 0, si hace rollback inserta. Así la unicidad la garantiza la
     * base de datos y no un "if (existe)" en Java, que tendría una carrera entre el SELECT y el INSERT.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (idempotency_key, request_hash, created_at)
            VALUES (:key, :hash, now())
            ON CONFLICT (idempotency_key) DO NOTHING
            """, nativeQuery = true)
    int claim(@Param("key") String key, @Param("hash") String hash);
}
