package cr.portfolio.payments.service;

public class InvalidSignatureException extends RuntimeException {
    public InvalidSignatureException() {
        super("Firma del webhook ausente o inválida");
    }
}
