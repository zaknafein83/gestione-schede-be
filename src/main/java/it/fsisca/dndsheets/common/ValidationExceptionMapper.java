package it.fsisca.dndsheets.common;

import jakarta.annotation.Priority;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mappa le {@link ConstraintViolationException} (Bean Validation) in risposte
 * RFC 7807 con {@code code="VALIDATION_FAILED"} e {@code detail} leggibile.
 *
 * <p>Il mapper di default di Quarkus restituisce solo {@code violations[]} senza
 * {@code code}/{@code detail}, che fa cadere il client su "Errore sconosciuto".
 * Qui produciamo lo stesso shape usato dal {@link RestExceptionMapper}, in modo
 * che il frontend possa riusare la stessa logica di parsing.
 */
@Provider
@Priority(1) // override del mapper di default di Quarkus per ConstraintViolationException
public class ValidationExceptionMapper implements ExceptionMapper<ConstraintViolationException> {

    private static final Logger LOG = Logger.getLogger(ValidationExceptionMapper.class);

    @Override
    public Response toResponse(ConstraintViolationException ex) {
        List<Map<String, String>> violations = new ArrayList<>();
        StringBuilder detail = new StringBuilder();

        for (ConstraintViolation<?> v : ex.getConstraintViolations()) {
            String field = lastNode(v.getPropertyPath());
            String message = v.getMessage();

            Map<String, String> entry = new HashMap<>();
            entry.put("field", field);
            entry.put("message", message);
            violations.add(entry);

            if (detail.length() > 0) detail.append("; ");
            detail.append(field).append(": ").append(message);
        }

        LOG.debugf("ConstraintViolation -> 400 VALIDATION_FAILED: %s", detail);

        Map<String, Object> body = new HashMap<>();
        body.put("type",       "about:blank");
        body.put("title",      Response.Status.BAD_REQUEST.getReasonPhrase());
        body.put("status",     Response.Status.BAD_REQUEST.getStatusCode());
        body.put("code",       "VALIDATION_FAILED");
        body.put("detail",     detail.toString());
        body.put("violations", violations);

        return Response.status(Response.Status.BAD_REQUEST)
                .type(RestExceptionMapper.PROBLEM_JSON)
                .entity(body)
                .build();
    }

    /** Ritorna l'ultimo nodo del property path (es. "update.payload.level" → "level"). */
    private static String lastNode(Path path) {
        String last = "";
        for (Path.Node node : path) {
            last = node.getName();
        }
        return last;
    }
}
