package cr.portfolio.payments.service;

import cr.portfolio.payments.domain.Direction;
import cr.portfolio.payments.domain.EntryKind;
import cr.portfolio.payments.domain.LedgerEntry;
import cr.portfolio.payments.domain.Payment;
import cr.portfolio.payments.domain.PaymentStatus;
import cr.portfolio.payments.repository.LedgerEntryRepository;
import cr.portfolio.payments.repository.PaymentRepository;
import cr.portfolio.payments.repository.WebhookEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    private final PaymentRepository payments;
    private final WebhookEventRepository events;
    private final LedgerEntryRepository ledger;
    private final AccountLocks locks;
    private final OutboxService outbox;

    public WebhookService(PaymentRepository payments, WebhookEventRepository events,
            LedgerEntryRepository ledger, AccountLocks locks, OutboxService outbox) {
        this.payments = payments;
        this.events = events;
        this.ledger = ledger;
        this.locks = locks;
        this.outbox = outbox;
    }

    /**
     * Aplica un evento que ya fue autenticado (firma) y validado por la capa web: este servicio no sabe
     * nada de HTTP, de JSON ni de firmas.
     *
     * <p>Todo ocurre en una sola transacción: si algo falla (transición inválida, saldo para revertir
     * insuficiente) no queda registrado el evento ni cambia el pago, y el proveedor puede reintentar.
     */
    @Transactional
    public WebhookOutcome handle(ProviderEventCommand event) {
        // 1) Bloquear el pago: dos eventos distintos sobre el mismo pago se procesan uno tras otro.
        var payment = payments.findByIdForUpdate(event.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Pago", event.paymentId()));

        // 2) Registrar el evento. Si ya estaba, es un reenvío: se responde 200 y no se hace nada más.
        int registered = events.register(event.eventId(), event.paymentId(), event.type().wire(), event.rawPayload());
        if (registered == 0) {
            return WebhookOutcome.DUPLICATE;
        }

        // 3) Aplicar el efecto.
        if (event.type() == ProviderEventType.PAYMENT_CONFIRMED) {
            payment.transitionTo(PaymentStatus.CONFIRMED);
            outbox.record(PaymentEventType.CONFIRMED, payment);
        } else {
            reverse(payment);
            payment.transitionTo(PaymentStatus.FAILED);
            outbox.record(PaymentEventType.FAILED, payment);
        }
        return WebhookOutcome.PROCESSED;
    }

    /**
     * Devuelve el dinero con asientos compensatorios (el ledger nunca se edita ni se borra).
     * Si el destino ya gastó los fondos, debit() lanza InsufficientFundsException y todo se revierte:
     * ver "Límites" en el README.
     */
    private void reverse(Payment payment) {
        var money = payment.money();
        var accounts = locks.lockInOrder(payment.getSourceAccountId(), payment.getDestinationAccountId());
        accounts.get(payment.getDestinationAccountId()).debit(money);
        accounts.get(payment.getSourceAccountId()).credit(money);

        ledger.save(LedgerEntry.of(payment.getId(), payment.getDestinationAccountId(),
                Direction.DEBIT, EntryKind.REVERSAL, money));
        ledger.save(LedgerEntry.of(payment.getId(), payment.getSourceAccountId(),
                Direction.CREDIT, EntryKind.REVERSAL, money));
    }
}
