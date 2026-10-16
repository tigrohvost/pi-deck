package dev.pideck.app.core;
import static org.junit.Assert.*;
import org.junit.Test;

public class InferenceLeaseTest {
    @Test public void lateIdleCannotReleaseANewTasksLease() {
        InferenceLease lease = new InferenceLease();
        lease.begin("server1", "task1");
        assertTrue(lease.end("server1", "task1"));
        lease.begin("server1", "task2");
        assertFalse(lease.end("server1", "task1"));
        assertFalse(lease.accepts("server1", "task1", false));
        assertFalse(lease.accepts("server1", "task1", true));
        assertTrue(lease.accepts("server1", "task2", true));
        lease.begin("server2", "task2");
        assertFalse(lease.end("server1", "task2"));
        lease.clear();
        assertFalse(lease.accepts("server2", "task2", true));
    }
}
