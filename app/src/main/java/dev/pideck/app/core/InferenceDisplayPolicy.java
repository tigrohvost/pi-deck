package dev.pideck.app.core;

/**
 * Window hints while local inference runs.
 *
 * <p>Decode on this class of phone is bound by shared memory bandwidth and thermal headroom. A
 * 120 Hz panel that recomposes the deck for minutes competes for both, while streamed text gains
 * nothing above 60 Hz. The hint is released as soon as inference ends.
 */
public final class InferenceDisplayPolicy {
    /** Refresh rate requested while a turn, prefill or model load is active. */
    public static final float INFERENCE_REFRESH_HZ = 60f;

    private InferenceDisplayPolicy() {
    }

    /** {@code 0} means "no preference", which returns the panel to the system's choice. */
    public static float preferredRefreshRate(boolean inferenceActive) {
        return inferenceActive ? INFERENCE_REFRESH_HZ : 0f;
    }

    public static boolean keepsScreenOn(boolean inferenceActive, boolean maximumSpeed) {
        return inferenceActive && maximumSpeed;
    }
}
