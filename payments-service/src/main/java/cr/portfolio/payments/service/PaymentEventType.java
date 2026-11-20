package cr.portfolio.payments.service;

/** Eventos de pago que este servicio publica hacia otros servicios. {@code wire()} es el nombre público del evento. */
public enum PaymentEventType {
    CREATED("payment.created"),
    CONFIRMED("payment.confirmed"),
    FAILED("payment.failed");

    private final String wire;

    PaymentEventType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
