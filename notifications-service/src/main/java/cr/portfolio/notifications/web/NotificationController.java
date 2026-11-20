package cr.portfolio.notifications.web;

import cr.portfolio.notifications.repository.NotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    public record NotificationResponse(UUID id, UUID paymentId, String eventType, String channel, String message,
            String status, Instant createdAt) {
    }

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    /** Notificaciones de un pago, en orden de creación. Sirve para ver el resultado y para las pruebas. */
    @GetMapping
    public List<NotificationResponse> byPayment(@RequestParam UUID paymentId) {
        return notifications.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(n -> new NotificationResponse(n.getId(), n.getPaymentId(), n.getEventType(), n.getChannel(),
                        n.getMessage(), n.getStatus(), n.getCreatedAt()))
                .toList();
    }
}
