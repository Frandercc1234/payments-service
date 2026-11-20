package cr.portfolio.payments.service;

import cr.portfolio.payments.config.WebhookProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifica la firma HMAC-SHA256 del proveedor sobre el cuerpo EXACTO recibido (bytes crudos).
 * Formato del header: {@code X-Signature: sha256=<hex>}.
 */
@Component
public class SignatureVerifier {

    private static final String PREFIX = "sha256=";
    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] secret;

    public SignatureVerifier(WebhookProperties properties) {
        this.secret = properties.secret().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] body, String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(header.substring(PREFIX.length()));
        } catch (IllegalArgumentException notHex) {
            return false;
        }
        // isEqual compara en tiempo constante: con equals() el tiempo de respuesta delata cuántos
        // bytes iniciales coinciden y permite adivinar la firma byte por byte (timing attack).
        return MessageDigest.isEqual(sign(body), provided);
    }

    public String sign(String body) {
        return PREFIX + HexFormat.of().formatHex(sign(body.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[] sign(byte[] body) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 no disponible", e);
        }
    }
}
