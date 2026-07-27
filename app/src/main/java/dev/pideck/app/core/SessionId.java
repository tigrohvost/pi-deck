package dev.pideck.app.core;

import java.util.Objects;
import java.util.UUID;

/**
 * Canonical Pi session identifier.
 *
 * <p>Pi 0.82.x creates UUIDv7 sessions. Android may create a UUIDv4 before the first Pi RPC
 * handshake, so both versions are valid here. Operation IDs deliberately remain UUIDv4-only.
 */
public final class SessionId {
    private final UUID value;

    private SessionId(UUID value) {
        this.value = value;
    }

    public static SessionId create() {
        return new SessionId(UUID.randomUUID());
    }

    public static SessionId parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("sessionId is missing");
        UUID parsed;
        try {
            parsed = UUID.fromString(raw);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid sessionId", error);
        }
        if (!parsed.toString().equals(raw)
                || (parsed.version() != 4 && parsed.version() != 7)) {
            throw new IllegalArgumentException(
                    "sessionId must be a canonical UUIDv4 or UUIDv7"
            );
        }
        return new SessionId(parsed);
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SessionId && value.equals(((SessionId) other).value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }
}
