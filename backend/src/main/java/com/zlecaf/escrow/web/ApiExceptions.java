package com.zlecaf.escrow.web;

/** Simple application exceptions mapped to HTTP statuses by the handler. */
public final class ApiExceptions {

    private ApiExceptions() {}

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) { super(message); }
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) { super(message); }
    }
}
