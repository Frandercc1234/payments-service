package cr.portfolio.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.portfolio.payments.domain.Direction;
import cr.portfolio.payments.domain.EntryKind;
import cr.portfolio.payments.domain.LedgerEntry;
import cr.portfolio.payments.domain.Payment;
import cr.portfolio.payments.domain.PaymentStatus;
import cr.portfolio.payments.repository.LedgerEntryRepository;
import cr.portfolio.payments.repository.PaymentRepository;
import cr.portfolio.payments.repository.WebhookEventRepository;
import cr.portfolio.payments.web.dto.WebhookDtos.EventType;
import cr.portfolio.payments.web.dto.WebhookDtos.ProviderEvent;
import cr.portfolio.payments.web.dto.WebhookDtos.WebhookAck;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WebhookService {

    private final SignatureVerifier signatures;
    private final ObjectMapper json;
    private final Validator validator;
    private final PaymentRepository payments;
    private final WebhookEventRepository events;
    private final LedgerEntryRepository ledger;
    private final AccountLocks locks;

    public WebhookService(SignatureVerifier signatures, ObjectMapper json, Validator validator,
            PaymentRepository payments, WebhookEventRepository events, LedgerEntryRepository ledger,
            AccountLocks locks) {
        this.signatures = signatures;
        this.json = json;
        this.validator = validator;
        this.payments = payments;
        this.events = events;
        this.ledger = ledger;
        this.locks = locks;
    }

    /**
     * Orden deliberado: 1) firma, 2) interpretar el cuerpo, 3) tocar la base de datos. Nada de lo que
     * venga en un cuerpo sin firma válida se interpreta ni llega a la base.
     *
     * <p>Todo ocurre en una sola transacción: si algo falla (transición inválida, saldo para revertir
     * insuficiente) no queda registrado el evento ni cambia el pago, y el proveedor puede reintentar.
     */
    @Transactional
    public WebhookAck handle(byte[] rawBody, String signatureHeader) {
        if (!signatures.isValid(rawBody, signatureHeader)) {
            throw new InvalidSignatureException();
        }
        var event = parse(rawBody);

        // 1) Bloquear el pago: dos eventos distintos sobre el mismo pago se procesan uno tras otro.
        var payment = payments.findByIdForUpdate(event.paymentId())
                .orElseThrow(() -> new ResourceNotFoundException("Pago", event.paymentId()));

        // 2) Registrar el evento. Si ya estaba, es un reenvío: se responde 200 y no se hace nada más.
        int registered = events.register(event.eventId(), event.paymentId(), event.type().wire(),
                new String(rawBody, StandardCharsets.UTF_8));
        if (registered == 0) {
            return WebhookAck.DUPLICATE;
        }

        // 3) Aplicar el efecto.
        if (event.type() == EventType.PAYMENT_CONFIRMED) {
            payment.transitionTo(PaymentStatus.CONFIRMED);
        } else {
            reverse(payment);
            payment.transitionTo(PaymentStatus.FAILED);
        }
        return WebhookAck.PROCESSED;
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

    private ProviderEvent parse(byte[] rawBody) {
        ProviderEvent event;
        try {
            event = json.readValue(rawBody, ProviderEvent.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cuerpo del evento inválido", e);
        }
        var violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return event;
    }
}
