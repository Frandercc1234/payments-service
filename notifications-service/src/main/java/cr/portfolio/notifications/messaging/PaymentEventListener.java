package cr.portfolio.notifications.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.portfolio.notifications.config.RabbitTopology;
import cr.portfolio.notifications.service.NotificationService;
import java.io.IOException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Recibe los eventos de pago. Un mensaje que NUNCA podrá procesarse (sin messageId, JSON ilegible, versión
 * desconocida) se rechaza sin reencolar: tras los reintentos configurados termina en la cola de mensajes muertos
 * (DLQ) en lugar de reintentarse para siempre.
 */
@Component
public class PaymentEventListener {

    private static final int SUPPORTED_VERSION = 1;

    private final ObjectMapper json;
    private final NotificationService notifications;

    public PaymentEventListener(ObjectMapper json, NotificationService notifications) {
        this.json = json;
        this.notifications = notifications;
    }

    @RabbitListener(queues = RabbitTopology.QUEUE)
    public void onMessage(Message message) {
        var messageId = message.getMessageProperties().getMessageId();
        if (messageId == null || messageId.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("Mensaje sin messageId: no se puede deduplicar");
        }
        notifications.process(messageId, parse(message.getBody()));
    }

    private PaymentEvent parse(byte[] body) {
        PaymentEvent event;
        try {
            event = json.readValue(body, PaymentEvent.class);
        } catch (IOException e) {
            throw new AmqpRejectAndDontRequeueException("Cuerpo del evento ilegible", e);
        }
        if (event.version() != SUPPORTED_VERSION) {
            throw new AmqpRejectAndDontRequeueException("Versión de evento no soportada: " + event.version());
        }
        if (event.type() == null || event.payment() == null || event.payment().id() == null) {
            throw new AmqpRejectAndDontRequeueException("Evento incompleto");
        }
        return event;
    }
}
