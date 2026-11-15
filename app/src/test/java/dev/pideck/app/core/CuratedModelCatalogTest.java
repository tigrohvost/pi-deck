package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** The shipped choices are separate from the broad historical parser fixture. */
public final class CuratedModelCatalogTest {
    @Test
    public void bundledCatalogHasExactlyThreeChoicesAndNoRetiredIds() throws Exception {
        ModelCatalog catalog = bundled();
        assertEquals(List.of("qwen3.5-0.8b", "lfm2.5-2.6b-qad", "bonsai2-9b"),
                catalog.all().stream().map(model -> model.id).collect(Collectors.toList()));
        assertFalse(catalog.byId("bonsai2-27b").isPresent());
        assertFalse(catalog.byId("qwen3.8-4b-distill").isPresent());
    }

    @Test
    public void defaultBalancesLatencyAndFallsBackToTheSmallProfileUnderPressure() throws Exception {
        ModelCatalog catalog = bundled();
        long gib = 1_073_741_824L;
        assertEquals("lfm2.5-2.6b-qad", catalog.recommend(12 * gib, false, 100 * gib).id);
        assertEquals("qwen3.5-0.8b", catalog.recommend(4 * gib, true, 100 * gib).id);
        assertFalse(ModelCatalog.isRecommendable(catalog.byId("bonsai2-9b").orElseThrow()));
        assertEquals("bonsai2-9b", catalog.byId("bonsai2-9b").orElseThrow().id);
    }

    private static ModelCatalog bundled() throws Exception {
        Path path = Path.of("src/main/assets/models-v2.json");
        if (!Files.exists(path)) path = Path.of("app/src/main/assets/models-v2.json");
        return ModelCatalog.parse(new String(Files.readAllBytes(path),
                java.nio.charset.StandardCharsets.UTF_8));
    }
}
