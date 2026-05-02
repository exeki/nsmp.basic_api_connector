package ru.kazantsev.nsmp.basic_api_connector.exception;

/**
 * Обертка над реальным исключением
 */
public class RequestProcessException extends RuntimeException {

    private static String getMessage(Throwable cause) {
        return "Something went wrong while executing the request. The actual exception is in the cause of this exception: " + cause.getClass().getName();
    }

    public RequestProcessException(Throwable cause) {
        super(getMessage(cause), cause);
    }
}
