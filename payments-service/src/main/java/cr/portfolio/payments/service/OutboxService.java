package cr.portfolio.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.portfolio.payments.domain.OutboxEvent;
import cr.portfolio.payments.domain.Payment;
import cr.portfolio.payments.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe eventos en la tabla outbox. No publica nada: eso lo hace el relay, en otro proceso.
 */
@Service
public class OutboxService {

    private static final String AGGREGATE_TYPE = "payment";

    private final OutboxEventRepository outbox;
    private final ObjectMapper json;

    public OutboxService(OutboxEventRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    /**
     * MANDATORY: el evento solo es correcto si se guarda dentro de la transacción del cambio de negocio.
     * Si alguien lo llamara sin transacción, el evento se guardaría aunque el pago después fallara
     * (o al revés): justo el problema que el outbox existe para evitar. Por eso se falla ruidosamente.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(PaymentEventType type, Payment payment) {
        var id = UUID.randomUUID();
        var now = Instant.now();
        var event = new PaymentEvent(id, PaymentEvent.CURRENT_VERSION, type.wire(), now, PaymentSnapshot.from(payment));
        outbox.save(OutboxEvent.of(id, AGGREGATE_TYPE, payment.getId(), type.wire(), write(event), now));
    }

    private String write(PaymentEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el evento", e);
        }
    }
}
