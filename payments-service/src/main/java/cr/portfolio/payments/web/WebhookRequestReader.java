package cr.portfolio.payments.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import cr.portfolio.payments.service.InvalidSignatureException;
import cr.portfolio.payments.service.ProviderEventCommand;
import cr.portfolio.payments.service.SignatureVerifier;
import cr.portfolio.payments.web.dto.WebhookDtos.ProviderEvent;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Puerta de entrada del webhook: convierte bytes crudos en un {@link ProviderEventCommand} confiable.
 *
 * <p>Orden deliberado: 1) firma, 2) interpretar el cuerpo. Nada de lo que venga en un cuerpo sin firma
 * válida se interpreta, y todo esto ocurre antes de abrir cualquier transacción.
 */
@Component
public class WebhookRequestReader {

    private final SignatureVerifier signatures;
    private final ObjectMapper json;
    private final Validator validator;

    public WebhookRequestReader(SignatureVerifier signatures, ObjectMapper json, Validator validator) {
        this.signatures = signatures;
        this.json = json;
        this.validator = validator;
    }

    public ProviderEventCommand read(byte[] rawBody, String signatureHeader) {
        if (!signatures.isValid(rawBody, signatureHeader)) {
            throw new InvalidSignatureException();
        }
        ProviderEvent event;
        try {
            event = json.readValue(rawBody, ProviderEvent.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cuerpo del evento inválido", e);
        }
        var violations = validator.validate(event);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return event.toCommand(new String(rawBody, StandardCharsets.UTF_8));
    }
}
