package com.smartchef.api.exception;

/**
 * Fallo del flujo de IA.
 *
 * <p>El mensaje puede llevar detalle interno (modelo, respuesta de Google), asi
 * que solo llega al cliente cuando se marca como {@link #userFacing}: el resto
 * se responde con un texto generico y el detalle queda en el log.
 */
public class GeminiServiceException extends RuntimeException {

    private final boolean userFacing;

    public GeminiServiceException(String message) {
        this(message, null, false);
    }

    public GeminiServiceException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private GeminiServiceException(String message, Throwable cause, boolean userFacing) {
        super(message, cause);
        this.userFacing = userFacing;
    }

    /** Mensaje redactado para el usuario final, seguro de mostrar tal cual. */
    public static GeminiServiceException userFacing(String message) {
        return new GeminiServiceException(message, null, true);
    }

    public boolean isUserFacing() {
        return userFacing;
    }
}
