package io.lexq.sdk;

/**
 * A response body that {@link JsonReader} cannot read. The message says what is wrong, gives the
 * offset when the body is UTF-8, and never quotes the body.
 */
final class UnreadableJsonException extends Exception {
    private static final long serialVersionUID = 1L;

    UnreadableJsonException(String message) {
        super(message);
    }
}
