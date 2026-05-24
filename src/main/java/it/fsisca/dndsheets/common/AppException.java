package it.fsisca.dndsheets.common;

import jakarta.ws.rs.core.Response;

/**
 * Eccezione applicativa con uno status HTTP associato.
 * Viene mappata a una risposta JSON RFC 7807 dal {@link RestExceptionMapper}.
 */
public class AppException extends RuntimeException {

    private final Response.Status status;
    private final String code;

    public AppException(Response.Status status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public Response.Status status() { return status; }
    public String code()             { return code;   }

    // Factory comode
    public static AppException conflict(String code, String message) {
        return new AppException(Response.Status.CONFLICT, code, message);
    }
    public static AppException badRequest(String code, String message) {
        return new AppException(Response.Status.BAD_REQUEST, code, message);
    }
    public static AppException notFound(String code, String message) {
        return new AppException(Response.Status.NOT_FOUND, code, message);
    }
    public static AppException unauthorized(String code, String message) {
        return new AppException(Response.Status.UNAUTHORIZED, code, message);
    }
    public static AppException forbidden(String code, String message) {
        return new AppException(Response.Status.FORBIDDEN, code, message);
    }
    public static AppException paymentRequired(String code, String message) {
        return new AppException(Response.Status.PAYMENT_REQUIRED, code, message);
    }
    public static AppException tooManyRequests(String code, String message) {
        return new AppException(Response.Status.TOO_MANY_REQUESTS, code, message);
    }
}
