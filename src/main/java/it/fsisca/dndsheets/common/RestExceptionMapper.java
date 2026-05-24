package it.fsisca.dndsheets.common;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Mappa le {@link AppException} in risposte JSON RFC 7807 (application/problem+json).
 */
@Provider
public class RestExceptionMapper implements ExceptionMapper<AppException> {

    /** Media type RFC 7807 standard. */
    public static final String PROBLEM_JSON = "application/problem+json";

    private static final Logger LOG = Logger.getLogger(RestExceptionMapper.class);

    @Override
    public Response toResponse(AppException ex) {
        LOG.debugf("AppException -> %d %s: %s", ex.status().getStatusCode(), ex.code(), ex.getMessage());

        Map<String, Object> body = new HashMap<>();
        body.put("type",   "about:blank");
        body.put("title",  ex.status().getReasonPhrase());
        body.put("status", ex.status().getStatusCode());
        body.put("code",   ex.code());
        body.put("detail", ex.getMessage());

        return Response.status(ex.status())
                .type(PROBLEM_JSON)
                .entity(body)
                .build();
    }
}
