package dev.schembot.schematic;

public final class SchematicException extends Exception {
    private static final long serialVersionUID = 1L;

    public SchematicException(String message) {
        super(message);
    }

    public SchematicException(String message, Throwable cause) {
        super(message, cause);
    }
}
