package az.etiket.backend;

final class LookupException extends RuntimeException {
    final int status;
    final String code;
    LookupException(int status, String code) { super(code); this.status = status; this.code = code; }
}
