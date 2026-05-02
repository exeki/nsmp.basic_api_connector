package ru.kazantsev.nsmp.basic_api_connector.exception;

import org.apache.hc.core5.http.ClassicHttpResponse;
import ru.kazantsev.nsmp.basic_api_connector.Connector;

import java.io.IOException;

/**
 * Исключение, которое выбрасывается при получении неуспешного HTTP-ответа.
 */
public class BadResponseException extends RuntimeException {

    protected static final int MAX_BODY_SIZE_IN_MESSAGE = 1000;

    protected ResponseSnapshot responseSnapshot;
    protected String host;

    public BadResponseException(Connector connector, ClassicHttpResponse response) throws IOException {
        this.responseSnapshot = new ResponseSnapshot(response);
        this.host = connector.getHost();
    }

    protected static boolean isTextContentType(String contentType) {
        if (contentType == null) {
            return false;
        }

        String value = contentType.toLowerCase();

        return value.startsWith("text/")
                || value.contains("json")
                || value.contains("xml")
                || value.contains("html");
    }

    @Override
    public String getMessage() {
        String message = "Error when accessing to " + host + ", response status: " + responseSnapshot.getStatus();
        if (isTextContentType(responseSnapshot.getContentType())) {
            var bodyString = responseSnapshot.getBodyAsString();
            message += ", message: " + bodyString.substring(0, Math.min(bodyString.length(), MAX_BODY_SIZE_IN_MESSAGE));
        }
        return message;
    }

    /**
     * Получить сохранённый снимок ответа сервера.
     *
     * @return снимок ответа сервера
     */
    @SuppressWarnings("unused")
    public ResponseSnapshot getResponseSnapshot() {
        return this.responseSnapshot;
    }

    /**
     * Выбрасывает исключение, если код ответа неуспешный.
     *
     * @param connector коннектор
     * @param response  ответ NSMP
     */
    @SuppressWarnings("unused")
    public static void throwIfNotOk(Connector connector, ClassicHttpResponse response) {
        try {
            int status = response.getCode();
            if (status >= 400 || status < 200) throw new BadResponseException(connector, response);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
