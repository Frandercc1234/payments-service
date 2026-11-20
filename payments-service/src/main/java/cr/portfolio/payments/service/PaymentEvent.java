package cr.portfolio.payments.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Contrato del evento tal como viaja por RabbitMQ (versión 1). Lo consume otro servicio, así que los
 * nombres de campo son una API pública: cambiarlos exige subir {@code version}.
 *
 * <p>{@code eventId} es el id de la fila del outbox y sirve al consumidor para descartar duplicados.
 */
public record PaymentEvent(UUID eventId, int version, String type, Instant occurredAt, PaymentSnapshot payment) {

    public static final int CURRENT_VERSION = 1;
}
