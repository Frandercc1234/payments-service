package cr.portfolio.payments.service;

import cr.portfolio.payments.domain.Direction;
import cr.portfolio.payments.domain.EntryKind;
import cr.portfolio.payments.domain.LedgerEntry;
import cr.portfolio.payments.domain.Money;
import cr.portfolio.payments.domain.Payment;
import cr.portfolio.payments.repository.LedgerEntryRepository;
import cr.portfolio.payments.repository.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private final PaymentRepository payments;
    private final LedgerEntryRepository ledger;
    private final AccountLocks locks;
    private final OutboxService outbox;

    public PaymentService(PaymentRepository payments, LedgerEntryRepository ledger, AccountLocks locks,
            OutboxService outbox) {
        this.payments = payments;
        this.ledger = ledger;
        this.locks = locks;
        this.outbox = outbox;
    }

    /**
     * Todo o nada: si algo lanza una RuntimeException (saldo insuficiente, moneda distinta),
     * Spring hace rollback y no queda ni el movimiento de saldo ni asientos a medias.
     * El pago nace PENDING: el dinero ya está reservado y el proveedor lo confirma o lo rechaza después.
     */
    @Transactional
    public Payment create(UUID sourceId, UUID destinationId, Money money) {
        var payment = Payment.create(sourceId, destinationId, money);   // valida origen != destino antes de bloquear

        var accounts = locks.lockInOrder(sourceId, destinationId);
        accounts.get(sourceId).debit(money);
        accounts.get(destinationId).credit(money);

        payments.save(payment);
        ledger.save(LedgerEntry.of(payment.getId(), sourceId, Direction.DEBIT, EntryKind.PAYMENT, money));
        ledger.save(LedgerEntry.of(payment.getId(), destinationId, Direction.CREDIT, EntryKind.PAYMENT, money));
        outbox.record(PaymentEventType.CREATED, payment);   // misma transacción: pago y evento, los dos o ninguno
        return payment;
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> new ResourceNotFoundException("Pago", id));
    }
}
