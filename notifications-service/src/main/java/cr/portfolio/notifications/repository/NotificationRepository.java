package cr.portfolio.notifications.repository;

import cr.portfolio.notifications.domain.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);
}
