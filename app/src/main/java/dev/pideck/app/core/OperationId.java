package dev.pideck.app.core;

import java.util.Objects;
import java.util.UUID;

/** Canonical UUIDv4 used unchanged from Android dispatch through runtime completion. */
public final class OperationId {
    private final UUID value;

    private OperationId(UUID value) {
        this.value = value;
    }

    public static OperationId create() {
        return new OperationId(UUID.randomUUID());
    }

    public static OperationId parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("operationId is missing");
        UUID parsed;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid operationId", error);
        }
        if (!parsed.toString().equals(raw) || parsed.version() != 4) {
            throw new IllegalArgumentException("operationId must be a canonical UUIDv4");
        }
        return new OperationId(parsed);
    }

    public UUID value() {
        return value;
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OperationId && value.equals(((OperationId) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
