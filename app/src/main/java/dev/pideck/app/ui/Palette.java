package dev.pideck.app.ui;

/**
 * Every colour the deck draws, in one place, for one scheme.
 *
 * <p>Deliberately free of {@code android.graphics} so the schemes stay unit-testable on a plain
 * JVM: panel fills are derived from the panel colour instead of being spelled out per widget, and
 * the values below are the only colour literals in the app.
 */
public final class Palette {
    public static final String SCHEME_NORD = "nord";
    public static final String SCHEME_DECK = "deck";

    /** Scheme identifier persisted in preferences. */
    public final String id;
    /** Human-readable name shown in CORE CONTROL. */
    public final String label;

    public final int background;
    public final int panel;
    public final int stroke;

    public final int accent;
    public final int accentAlt;
    public final int ok;
    public final int warn;
    /** Border and stripe colour for faults; too dark for body text in Nord. */
    public final int error;
    /** Fault colour lifted far enough off the panel to be read as text. */
    public final int errorText;

    public final int text;
    public final int muted;
    public final int hint;

    public final int backdropStart;
    public final int backdropMid;
    public final int backdropEnd;
    /** Backdrop grid lines, alpha included. */
    public final int gridLine;
    /** Diagonal backdrop glow, alpha included. */
    public final int glow;
    /** Scanline overlay stripe, alpha included. */
    public final int scanline;
    /** Horizontal vignette edge, alpha included. */
    public final int vignette;

    private Palette(
            String id,
            String label,
            int background,
            int panel,
            int stroke,
            int accent,
            int accentAlt,
            int ok,
            int warn,
            int error,
            int errorText,
            int text,
            int muted,
            int hint,
            int backdropStart,
            int backdropMid,
            int backdropEnd,
            int gridLine,
            int glow,
            int scanline,
            int vignette
    ) {
        this.id = id;
        this.label = label;
        this.background = background;
        this.panel = panel;
        this.stroke = stroke;
        this.accent = accent;
        this.accentAlt = accentAlt;
        this.ok = ok;
        this.warn = warn;
        this.error = error;
        this.errorText = errorText;
        this.text = text;
        this.muted = muted;
        this.hint = hint;
        this.backdropStart = backdropStart;
        this.backdropMid = backdropMid;
        this.backdropEnd = backdropEnd;
        this.gridLine = gridLine;
        this.glow = glow;
        this.scanline = scanline;
        this.vignette = vignette;
    }

    public static Palette forId(String id) {
        return SCHEME_DECK.equals(id) ? deck() : nord();
    }

    /** Canonical Nord: Polar Night surfaces, Frost and Aurora accents. */
    public static Palette nord() {
        return new Palette(
                SCHEME_NORD,
                "NORD // POLAR NIGHT",
                0xFF2E3440,          // background  nord0
                0xFF3B4252,          // panel       nord1
                0xFF4C566A,          // stroke      nord3
                0xFF88C0D0,          // accent      nord8
                0xFFB48EAD,          // accentAlt   nord15
                0xFFA3BE8C,          // ok          nord14
                0xFFEBCB8B,          // warn        nord13
                0xFFBF616A,          // error       nord11
                0xFFD08E96,          // errorText   nord11 lifted
                0xFFECEFF4,          // text        nord6
                0xFF8A97B0,          // muted       between nord3 and nord4
                0xFF6C7893,          // hint
                0xFF2E3440,
                0xFF333B4A,
                0xFF3B4252,
                0x144C566A,          // gridLine    nord3 at 8%
                0x0EB48EAD,          // glow        nord15 at 5%
                0x0A000000,
                0x1E000000
        );
    }

    /** The original deck neon, pulled down by roughly a third in luminance. */
    public static Palette deck() {
        return new Palette(
                SCHEME_DECK,
                "DECK // DIMMED NEON",
                0xFF070B10,
                0xFF0B1119,
                0xFF1D2A33,
                0xFF46C6CE,          // was 40F7FF
                0xFFC24BA6,          // was FF2BD6
                0xFFA8C24A,          // was D6FF39
                0xFFC98A3C,          // was FFAA36
                0xFFC9566E,          // was FF4F74
                0xFFDB8494,
                0xFFC3D2D8,          // was DAE9EE
                0xFF6A808A,          // was 718B96
                0xFF465C66,
                0xFF04070C,
                0xFF080513,
                0xFF04090C,
                0x1246C6CE,          // gridLine    accent at 7%
                0x0FC24BA6,          // glow        accentAlt at 6%
                0x0D000000,
                0x2A000000
        );
    }

    /** Panel colour at the given alpha, for surfaces that sit over the backdrop. */
    public int fill(int alpha) {
        return withAlpha(panel, alpha);
    }

    /**
     * Panel colour pulled toward {@code role} by {@code mix} (0..1) at the given alpha, so every
     * tinted surface stays anchored to the scheme instead of carrying its own literal.
     */
    public int fill(int role, float mix, int alpha) {
        return withAlpha(blend(panel, role, mix), alpha);
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private static int blend(int from, int to, float amount) {
        float weight = Math.max(0f, Math.min(1f, amount));
        int red = channel(from, 16) + Math.round((channel(to, 16) - channel(from, 16)) * weight);
        int green = channel(from, 8) + Math.round((channel(to, 8) - channel(from, 8)) * weight);
        int blue = channel(from, 0) + Math.round((channel(to, 0) - channel(from, 0)) * weight);
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    private static int channel(int color, int shift) {
        return (color >> shift) & 0xFF;
    }
}
