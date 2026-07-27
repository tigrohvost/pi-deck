package dev.pideck.app.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public final class SessionIdTest {
    private static final String UUID_V7 = "01890f76-e8b2-7cc2-98c8-8c4a7ef8d123";

    @Test
    public void acceptsCanonicalUuid4AndPiUuid7() {
        String uuid4 = SessionId.create().toString();
        assertEquals(uuid4, SessionId.parse(uuid4).toString());
        assertEquals(UUID_V7, SessionId.parse(UUID_V7).toString());
    }

    @Test
    public void rejectsOtherVersionsAndNonCanonicalText() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionId.parse("01890f76-e8b2-1cc2-98c8-8c4a7ef8d123")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> SessionId.parse(UUID_V7.toUpperCase())
        );
    }
}
