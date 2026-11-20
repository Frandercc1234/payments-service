package cr.portfolio.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import cr.portfolio.payments.repository.IdempotencyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotencia de POST /payments.
 *
 * <p>Reclamar la clave y crear el pago ocurren en la MISMA transacción: o quedan los dos o ninguno.
 * Por eso un intento que falla (saldo insuficiente, cuenta inexistente) hace rollback también de la
 * clave y no la consume: el cliente puede reintentar con la misma clave cuando corrija la causa.
 * Solo los resultados exitosos quedan guardados y se repiten.
 */
@Service
public class IdempotentPaymentService {

    public record Result(PaymentSnapshot payment, int status, boolean replayed) {
    }

    private final IdempotencyRepository keys;
    private final PaymentService payments;
    private final ObjectMapper json;

    public IdempotentPaymentService(IdempotencyRepository keys, PaymentService payments, ObjectMapper json) {
        this.keys = keys;
        this.payments = payments;
        this.json = json;
    }

    @Transactional
    public Result create(String key, CreatePaymentCommand command) {
        var hash = fingerprint(command);

        if (keys.claim(key, hash) == 1) {
            var payment = payments.create(command.sourceAccountId(), command.destinationAccountId(), command.amount());
            var snapshot = PaymentSnapshot.from(payment);
            keys.findById(key).orElseThrow().complete(HttpStatus.CREATED.value(), write(snapshot));
            return new Result(snapshot, HttpStatus.CREATED.value(), false);
        }

        var existing = keys.findById(key).orElseThrow();
        if (!existing.getRequestHash().equals(hash)) {
            throw new IdempotencyConflictException(key);
        }
        return new Result(read(existing.getResponseBody()), existing.getResponseStatus(), true);
    }

    /**
     * Huella del contenido del pago. Se calcula sobre los campos ya interpretados, no sobre el texto
     * del JSON, así que el orden de los campos o los espacios no cambian el resultado.
     */
    private static String fingerprint(CreatePaymentCommand c) {
        var canonical = "%s|%s|%d|%s".formatted(c.sourceAccountId(), c.destinationAccountId(),
                c.amount().cents(), c.amount().currency());
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private String write(PaymentSnapshot body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la respuesta", e);
        }
    }

    private PaymentSnapshot read(String body) {
        try {
            return json.readValue(body, PaymentSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Respuesta guardada ilegible", e);
        }
    }
}
