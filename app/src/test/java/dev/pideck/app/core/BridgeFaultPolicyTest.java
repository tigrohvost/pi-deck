package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class BridgeFaultPolicyTest {
    @Test
    public void coldStartPollingFailureDoesNotInventACrash() {
        assertEquals(
                "",
                BridgeFaultPolicy.afterDisconnect(
                        false,
                        false,
                        "",
                        "Connection refused"
                )
        );
    }

    @Test
    public void anEstablishedBridgeDisconnectIsVisible() {
        assertEquals(
                "Connection reset",
                BridgeFaultPolicy.afterDisconnect(
                        true,
                        true,
                        "",
                        " Connection reset "
                )
        );
    }

    @Test
    public void pollingRetriesPreserveARealDisconnect() {
        assertEquals(
                "Connection reset",
                BridgeFaultPolicy.afterDisconnect(
                        false,
                        false,
                        "Connection reset",
                        "Connection refused"
                )
        );
    }

    @Test
    public void pollingRetriesPreserveAnExplicitLaunchFailure() {
        assertEquals(
                "Bridge process exited with code 1",
                BridgeFaultPolicy.afterDisconnect(
                        false,
                        false,
                        "Bridge process exited with code 1",
                        "Connection refused"
                )
        );
    }

    @Test
    public void missingDisconnectDetailStillHasAUsefulMessage() {
        assertEquals(
                "RPC bridge отключён",
                BridgeFaultPolicy.afterDisconnect(true, false, "", " ")
        );
    }
}
