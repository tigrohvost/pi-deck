package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TermuxEnvironmentTest {
    private static final String FDROID =
            "228fb2cfe90831c1499ec3ccaf61e96e8e1ce70766b9474672ce427334d41c42";
    private static final String GITHUB =
            "b6da01480eefd5fbf2cd3771b8d1021ec791304bdd6c4bf41d3faabad48ee5e1";

    @Test
    public void versionComparisonHandlesStableAndBetaVersions() {
        assertTrue(TermuxEnvironment.versionAtLeast("0.118.0", "0.118.0"));
        assertTrue(TermuxEnvironment.versionAtLeast("0.118.3", "0.118.0"));
        assertTrue(TermuxEnvironment.versionAtLeast("0.119.0-beta.3", "0.118.0"));
        assertFalse(TermuxEnvironment.versionAtLeast("0.117", "0.118.0"));
        assertFalse(TermuxEnvironment.versionAtLeast("unknown", "0.118.0"));
    }

    @Test
    public void signerClassificationDistinguishesSharedGithubKey() {
        assertEquals(
                TermuxEnvironment.Source.F_DROID,
                TermuxEnvironment.classifySigner(FDROID, FDROID, GITHUB)
        );
        assertEquals(
                TermuxEnvironment.Source.GITHUB_SHARED_TEST_KEY,
                TermuxEnvironment.classifySigner(GITHUB, FDROID, GITHUB)
        );
        assertEquals(
                TermuxEnvironment.Source.UNKNOWN,
                TermuxEnvironment.classifySigner("00", FDROID, GITHUB)
        );
    }
}
