package cr.portfolio.payments.service;

import java.util.UUID;

/**
 * Evento del proveedor ya autenticado y validado, expresado en términos del servicio (sin depender de la capa web).
 * {@code rawPayload} es el cuerpo original, que se guarda tal cual junto al evento.
 */
public record ProviderEventCommand(String eventId, ProviderEventType type, UUID paymentId, String rawPayload) {
}
