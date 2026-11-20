package cr.portfolio.notifications.service;

import cr.portfolio.notifications.messaging.PaymentEvent;

/** Texto de cada notificación. Los montos llegan en centavos (long): se formatean con enteros, sin double. */
final class NotificationText {

    private NotificationText() {
    }

    static String of(PaymentEvent event) {
        var p = event.payment();
        var amount = "%s %d,%02d".formatted(p.currency(), p.amount() / 100, p.amount() % 100);
        return switch (event.type()) {
            case "payment.created" -> "Pago %s recibido por %s (pendiente de confirmación)".formatted(p.id(), amount);
            case "payment.confirmed" -> "Pago %s por %s confirmado".formatted(p.id(), amount);
            case "payment.failed" -> "Pago %s por %s falló; el monto fue devuelto".formatted(p.id(), amount);
            default -> "Evento %s del pago %s por %s".formatted(event.type(), p.id(), amount);
        };
    }
}
