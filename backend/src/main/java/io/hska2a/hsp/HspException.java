package io.hska2a.hsp;

public class HspException extends RuntimeException {
    public HspException(String message) { super(message); }
    public HspException(String message, Throwable cause) { super(message, cause); }
}
