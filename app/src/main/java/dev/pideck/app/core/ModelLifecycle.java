package dev.pideck.app.core;

/**
 * Where a catalog model stands on this phone and what the CORE screen offers for it.
 *
 * <p>The precedence is the whole policy: a phone that cannot hold the weights offers nothing,
 * a private copy beats any download state, a running download can only be cancelled, and a
 * verified incoming file is installed before anything is downloaded again. The Activity maps
 * the result to labels and handlers; the decision itself is tested on the JVM.
 */
public final class ModelLifecycle {
    public enum Stage {
        NO_RAM,
        ACTIVE,
        INSTALLED,
        DOWNLOADING,
        DOWNLOAD_FAILED,
        VERIFIED,
        INCOMING,
        MISSING
    }

    public enum Action {
        NONE,
        SELECT,
        RESTART,
        CANCEL_DOWNLOAD,
        RETRY_DOWNLOAD,
        INSTALL,
        VERIFY,
        DOWNLOAD
    }

    public enum Secondary {
        NONE,
        DELETE_SOURCE,
        ATTACH_FILE
    }

    /** What is known about one model at render time. */
    public static final class Facts {
        public boolean fits;
        public boolean privateReady;
        public boolean selected;
        public boolean serverReady;
        public boolean downloadActive;
        public boolean downloadFailed;
        public boolean incoming;
        public boolean verified;
    }

    private ModelLifecycle() {
    }

    public static Stage stage(Facts facts) {
        if (!facts.fits) return Stage.NO_RAM;
        if (facts.privateReady && facts.selected && facts.serverReady) return Stage.ACTIVE;
        if (facts.privateReady) return Stage.INSTALLED;
        if (facts.downloadActive) return Stage.DOWNLOADING;
        if (facts.downloadFailed) return Stage.DOWNLOAD_FAILED;
        if (facts.incoming && facts.verified) return Stage.VERIFIED;
        if (facts.incoming) return Stage.INCOMING;
        return Stage.MISSING;
    }

    public static Action action(Stage stage, boolean selected) {
        return switch (stage) {
            case NO_RAM, ACTIVE -> Action.NONE;
            case INSTALLED -> selected ? Action.RESTART : Action.SELECT;
            case DOWNLOADING -> Action.CANCEL_DOWNLOAD;
            case DOWNLOAD_FAILED -> Action.RETRY_DOWNLOAD;
            case VERIFIED -> Action.INSTALL;
            case INCOMING -> Action.VERIFY;
            case MISSING -> Action.DOWNLOAD;
        };
    }

    /**
     * A shared incoming file can be deleted; a model the deck holds no bytes of can instead be
     * attached from a file the user already has, so the pinned artifact is never paid for twice.
     */
    public static Secondary secondary(Stage stage, Facts facts) {
        if (facts.incoming) return Secondary.DELETE_SOURCE;
        boolean noBytes = stage == Stage.MISSING || stage == Stage.DOWNLOAD_FAILED;
        return noBytes && facts.fits ? Secondary.ATTACH_FILE : Secondary.NONE;
    }
}
