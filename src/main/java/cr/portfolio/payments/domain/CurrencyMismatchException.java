package cr.portfolio.payments.domain;

public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(Currency expected, Currency actual) {
        super("Moneda incompatible: se esperaba " + expected + " y llegó " + actual);
    }
}
