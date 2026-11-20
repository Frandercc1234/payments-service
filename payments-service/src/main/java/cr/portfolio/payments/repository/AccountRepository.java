package cr.portfolio.payments.repository;

import cr.portfolio.payments.domain.Account;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data implementa esta interfaz en tiempo de ejecución: no hay clase que escribir.
 * Las consultas simples salen del nombre del método; las especiales se declaran con @Query.
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * SELECT ... FOR UPDATE. La fila queda bloqueada hasta que termina la transacción; cualquier otra
     * transacción que pida la misma fila espera. Así dos pagos simultáneos no leen el mismo saldo viejo.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
