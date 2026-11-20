package cr.portfolio.notifications.service;

import cr.portfolio.notifications.domain.Notification;
import cr.portfolio.notifications.messaging.PaymentEvent;
import cr.portfolio.notifications.repository.NotificationRepository;
import cr.portfolio.notifications.repository.ProcessedMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    public enum Outcome { NOTIFIED, DUPLICATE }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final ProcessedMessageRepository processed;
    private final NotificationRepository notifications;

    public NotificationService(ProcessedMessageRepository processed, NotificationRepository notifications) {
        this.processed = processed;
        this.notifications = notifications;
    }

    /**
     * Consumidor idempotente. Reclamar el messageId y crear la notificación ocurren en UNA transacción:
     * <ul>
     *   <li>si algo falla, se deshace todo (el mensaje no queda "procesado" sin efecto) y puede reintentarse;</li>
     *   <li>si el mensaje llega dos veces (el productor garantiza "al menos una vez"), la segunda no hace nada.</li>
     * </ul>
     * El mensaje se confirma (ack) al broker DESPUÉS de este commit: si el proceso muere entre medias, el broker
     * lo reentrega y el messageId ya reclamado lo descarta.
     */
    @Transactional
    public Outcome process(String messageId, PaymentEvent event) {
        if (processed.claim(messageId) == 0) {
            log.info("Mensaje {} ya procesado; se ignora el duplicado", messageId);
            return Outcome.DUPLICATE;
        }
        var text = NotificationText.of(event);
        notifications.save(Notification.sent(messageId, event.payment().id(), event.type(), "LOG", text));
        // Canal simulado: no se envía ningún correo ni SMS real.
        log.info("NOTIFICACION [{}] {}", event.type(), text);
        return Outcome.NOTIFIED;
    }
}
