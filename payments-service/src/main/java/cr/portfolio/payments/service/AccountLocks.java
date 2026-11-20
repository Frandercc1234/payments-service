package cr.portfolio.payments.service;

import cr.portfolio.payments.domain.Account;
import cr.portfolio.payments.repository.AccountRepository;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bloquea cuentas siempre en el mismo orden (por ID ascendente).
 *
 * <p>Sin orden fijo hay deadlock: el pago A→B bloquea A y espera B, mientras el pago B→A bloquea B y
 * espera A. Con orden fijo ambos piden primero la misma cuenta y uno simplemente espera al otro.
 */
@Component
public class AccountLocks {

    private final AccountRepository accounts;

    public AccountLocks(AccountRepository accounts) {
        this.accounts = accounts;
    }

    /** MANDATORY: el bloqueo solo dura lo que dura una transacción, así que debe existir una abierta. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockInOrder(UUID first, UUID second) {
        var locked = new TreeMap<UUID, Account>();   // TreeMap itera en orden de clave
        for (UUID id : new java.util.TreeSet<>(java.util.List.of(first, second))) {
            locked.put(id, accounts.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Cuenta", id)));
        }
        return locked;
    }
}
