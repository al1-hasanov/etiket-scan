package az.etiket.backend;

final class LookupException extends RuntimeException {
    final int status;
    final String code;
    final int retryAfterSeconds;

    LookupException(int status, String code) { this(status, code, 0); }
    LookupException(int status, String code, int retryAfterSeconds) {
        super(code);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }
}
