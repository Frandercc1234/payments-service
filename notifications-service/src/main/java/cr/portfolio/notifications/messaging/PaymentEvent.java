package cr.portfolio.notifications.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato del evento tal como lo publica payments-service (versión 1). Es una copia propia a propósito:
 * los dos servicios no comparten código, solo este contrato. Los campos desconocidos se ignoran, de modo
 * que el productor puede agregar campos sin romper a este consumidor.
 */
public record PaymentEvent(UUID eventId, int version, String type, Instant occurredAt, Payment payment) {

    public record Payment(UUID id, UUID sourceAccountId, UUID destinationAccountId, long amount, String currency,
            String status) {
    }
}
