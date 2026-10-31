package com.ridehail.common;

public final class ApiExceptions {
    private ApiExceptions() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String what, Object id) { super(what + " not found: " + id); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) { super(message); }
    }

    /** The payment provider could not be reached or rejected a setup call; the client may retry. */
    public static class BadGatewayException extends RuntimeException {
        public BadGatewayException(String message, Throwable cause) { super(message, cause); }
    }

    /** Too many requests from this caller; they may retry after the given delay. */
    public static class TooManyRequestsException extends RuntimeException {
        private final long retryAfterSeconds;

        public TooManyRequestsException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() { return retryAfterSeconds; }
    }

    /** The request itself is wrong (400), as opposed to wrong for the current state (409). */
    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) { super(message); }
    }
    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(String message) { super(message); }
    }
    public static class UnsupportedMediaException extends RuntimeException {
        public UnsupportedMediaException(String message) { super(message); }
    }
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }
}
