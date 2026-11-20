package cr.portfolio.payments.web;

import cr.portfolio.payments.domain.CurrencyMismatchException;
import cr.portfolio.payments.domain.InsufficientFundsException;
import cr.portfolio.payments.domain.InvalidStateTransitionException;
import cr.portfolio.payments.service.IdempotencyConflictException;
import cr.portfolio.payments.service.InvalidSignatureException;
import cr.portfolio.payments.service.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Convierte toda excepción en un ProblemDetail (RFC 7807, {@code application/problem+json}).
 * Extender ResponseEntityExceptionHandler cubre además los errores propios de Spring MVC
 * (JSON ilegible, método no permitido, header faltante) con el mismo formato.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String TYPE_PREFIX = "urn:payments:problem:";

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail notFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "Recurso no encontrado", ex.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds", "Saldo insuficiente", ex.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail currencyMismatch(CurrencyMismatchException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "currency-mismatch", "Moneda incompatible", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ProblemDetail invalidTransition(InvalidStateTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "invalid-state-transition", "Transición de estado inválida", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException ex) {
        return problem(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency-Key reutilizada", ex.getMessage());
    }

    @ExceptionHandler(InvalidSignatureException.class)
    ProblemDetail invalidSignature(InvalidSignatureException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-signature", "Firma inválida", ex.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "validation-error", "Datos inválidos", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail illegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Solicitud inválida", ex.getMessage());
    }

    /**
     * Red de seguridad. El detalle real (stack trace, mensaje de la base de datos) va al log y NUNCA
     * a la respuesta: un error SQL puede revelar nombres de tablas, constraints o datos.
     */
    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception ex) {
        log.error("Error no controlado", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Error interno",
                "Ocurrió un error inesperado. Intentá de nuevo más tarde.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> Map.of("field", e.getField(), "message", String.valueOf(e.getDefaultMessage())))
                .toList();
        var pd = problem(HttpStatus.BAD_REQUEST, "validation-error", "Datos inválidos",
                "Uno o más campos no cumplen las reglas de validación");
        pd.setProperty("errors", errors);
        return handleExceptionInternal(ex, pd, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        var pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_PREFIX + code));
        pd.setTitle(title);
        return pd;
    }
}
