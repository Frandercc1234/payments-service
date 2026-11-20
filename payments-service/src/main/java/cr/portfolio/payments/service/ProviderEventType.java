package cr.portfolio.payments.service;

/** Tipos de evento que el servicio sabe procesar. {@code wire()} es el texto con que se registran en la base. */
public enum ProviderEventType {
    PAYMENT_CONFIRMED("payment.confirmed"),
    PAYMENT_FAILED("payment.failed");

    private final String wire;

    ProviderEventType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
