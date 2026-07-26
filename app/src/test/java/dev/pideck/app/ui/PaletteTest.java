package dev.pideck.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PaletteTest {
    /** Luminance of the neon values the deck shipped with, for the dimming check below. */
    private static final int OLD_CYAN = 0xFF40F7FF;
    private static final int OLD_MAGENTA = 0xFFFF2BD6;
    private static final int OLD_LIME = 0xFFD6FF39;
    private static final int OLD_ORANGE = 0xFFFFAA36;
    private static final int OLD_RED = 0xFFFF4F74;

    @Test
    public void nordUsesTheCanonicalPalette() {
        Palette nord = Palette.nord();
        assertEquals(0xFF2E3440, nord.background);   // nord0
        assertEquals(0xFF3B4252, nord.panel);        // nord1
        assertEquals(0xFF4C566A, nord.stroke);       // nord3
        assertEquals(0xFF88C0D0, nord.accent);       // nord8
        assertEquals(0xFFB48EAD, nord.accentAlt);    // nord15
        assertEquals(0xFFA3BE8C, nord.ok);           // nord14
        assertEquals(0xFFEBCB8B, nord.warn);         // nord13
        assertEquals(0xFFBF616A, nord.error);        // nord11
        assertEquals(0xFFECEFF4, nord.text);         // nord6
    }

    @Test
    public void unknownSchemeFallsBackToNord() {
        assertEquals(Palette.SCHEME_NORD, Palette.forId(null).id);
        assertEquals(Palette.SCHEME_NORD, Palette.forId("neon-1998").id);
        assertEquals(Palette.SCHEME_NORD, Palette.forId(Palette.SCHEME_NORD).id);
        assertEquals(Palette.SCHEME_DECK, Palette.forId(Palette.SCHEME_DECK).id);
    }

    @Test
    public void everySurfaceColorIsOpaque() {
        for (Palette palette : List.of(Palette.nord(), Palette.deck())) {
            for (int color : List.of(
                    palette.background, palette.panel, palette.stroke,
                    palette.accent, palette.accentAlt, palette.ok, palette.warn,
                    palette.error, palette.errorText, palette.text, palette.muted, palette.hint,
                    palette.backdropStart, palette.backdropMid, palette.backdropEnd
            )) {
                assertEquals(palette.id, 0xFF, (color >>> 24) & 0xFF);
            }
        }
    }

    @Test
    public void overlaysStayTranslucent() {
        for (Palette palette : List.of(Palette.nord(), Palette.deck())) {
            for (int overlay : List.of(
                    palette.gridLine, palette.glow, palette.scanline, palette.vignette
            )) {
                int alpha = (overlay >>> 24) & 0xFF;
                assertTrue(palette.id + " alpha=" + alpha, alpha > 0 && alpha < 0x40);
            }
        }
    }

    @Test
    public void bodyTextClearsTheEnhancedContrastBar() {
        for (Palette palette : List.of(Palette.nord(), Palette.deck())) {
            assertContrast(palette.id + " text", palette.text, palette.panel, 7.0);
        }
    }

    @Test
    public void everyLabelColorStaysReadableOnItsPanel() {
        for (Palette palette : List.of(Palette.nord(), Palette.deck())) {
            assertContrast(palette.id + " accent", palette.accent, palette.panel, 3.0);
            assertContrast(palette.id + " accentAlt", palette.accentAlt, palette.panel, 3.0);
            assertContrast(palette.id + " ok", palette.ok, palette.panel, 3.0);
            assertContrast(palette.id + " warn", palette.warn, palette.panel, 3.0);
            assertContrast(palette.id + " errorText", palette.errorText, palette.panel, 3.0);
            assertContrast(palette.id + " muted", palette.muted, palette.panel, 3.0);
        }
    }

    @Test
    public void deckSchemeIsDimmerThanTheNeonItReplaces() {
        Palette deck = Palette.deck();
        assertDimmer("accent", deck.accent, OLD_CYAN);
        assertDimmer("accentAlt", deck.accentAlt, OLD_MAGENTA);
        assertDimmer("ok", deck.ok, OLD_LIME);
        assertDimmer("warn", deck.warn, OLD_ORANGE);
        assertDimmer("error", deck.error, OLD_RED);
    }

    @Test
    public void bothSchemesGlareLessThanTheOriginalNeon() {
        // Harshness is the contrast the brightest accent throws against its own background, not
        // raw luminance: Polar Night lifts the surface, the deck scheme pulls the accents down.
        double neon = peakAccentContrast(0xFF030509, OLD_CYAN, OLD_MAGENTA, OLD_LIME, OLD_ORANGE);
        double nord = peakAccentContrast(Palette.nord());
        double deck = peakAccentContrast(Palette.deck());

        assertTrue("nord " + round(nord) + " vs neon " + round(neon), nord < neon);
        assertTrue("deck " + round(deck) + " vs neon " + round(neon), deck < neon);
        assertTrue("nord " + round(nord) + " vs deck " + round(deck), nord < deck);
    }

    @Test
    public void derivedFillsKeepTheRequestedAlphaAndStayAnchoredToThePanel() {
        Palette nord = Palette.nord();
        assertEquals(0xD8, (nord.fill(0xD8) >>> 24) & 0xFF);
        assertEquals(nord.panel & 0x00FFFFFF, nord.fill(0xD8) & 0x00FFFFFF);

        int tinted = nord.fill(nord.error, 0.5f, 0xBC);
        assertEquals(0xBC, (tinted >>> 24) & 0xFF);
        for (int shift : List.of(16, 8, 0)) {
            int from = (nord.panel >> shift) & 0xFF;
            int to = (nord.error >> shift) & 0xFF;
            int mixed = (tinted >> shift) & 0xFF;
            assertTrue(
                    "channel " + shift + " = " + mixed,
                    mixed >= Math.min(from, to) && mixed <= Math.max(from, to)
            );
        }
    }

    private static double peakAccentContrast(Palette palette) {
        return peakAccentContrast(
                palette.background,
                palette.accent, palette.accentAlt, palette.ok, palette.warn
        );
    }

    private static double peakAccentContrast(int background, int... accents) {
        double peak = 0;
        for (int accent : accents) {
            peak = Math.max(peak, contrast(accent, background));
        }
        return peak;
    }

    private static void assertDimmer(String role, int now, int before) {
        assertTrue(
                role + ": " + luminance(now) + " should be below " + luminance(before),
                luminance(now) < luminance(before)
        );
    }

    private static void assertContrast(String what, int foreground, int background, double minimum) {
        double ratio = contrast(foreground, background);
        assertTrue(what + " contrast " + round(ratio) + " < " + minimum, ratio >= minimum);
    }

    private static double contrast(int foreground, int background) {
        double bright = Math.max(luminance(foreground), luminance(background));
        double dark = Math.min(luminance(foreground), luminance(background));
        return (bright + 0.05) / (dark + 0.05);
    }

    /** WCAG 2.1 relative luminance. */
    private static double luminance(int color) {
        return 0.2126 * linear((color >> 16) & 0xFF)
                + 0.7152 * linear((color >> 8) & 0xFF)
                + 0.0722 * linear(color & 0xFF);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
