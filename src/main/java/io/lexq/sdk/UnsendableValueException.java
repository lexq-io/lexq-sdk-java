package io.lexq.sdk;

/**
 * A value in the facts or contexts that the client does not send. The message names where the
 * value is, such as {@code facts.items[3]}, and never the value itself, except to say that it is
 * NaN or an infinity.
 */
final class UnsendableValueException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;

    UnsendableValueException(String message) {
        super(message);
    }
}
