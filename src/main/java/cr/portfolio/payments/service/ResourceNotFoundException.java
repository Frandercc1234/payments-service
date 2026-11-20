package cr.portfolio.payments.service;

import java.util.UUID;

public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, UUID id) {
        super(resource + " no encontrada: " + id);
    }
}
