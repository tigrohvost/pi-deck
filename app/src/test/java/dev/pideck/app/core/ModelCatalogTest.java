package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModelCatalogTest {
    private static final long GIB = 1_073_741_824L;

    @Test
    public void recommendationTracksDeviceMemory() {
        assertEquals(ModelCatalog.NANO, ModelCatalog.recommend(3L * GIB, 20L * GIB));
        assertEquals(ModelCatalog.EDGE, ModelCatalog.recommend(6L * GIB, 20L * GIB));
        assertEquals(ModelCatalog.CORE, ModelCatalog.recommend(8L * GIB, 20L * GIB));
        assertEquals(ModelCatalog.CORE, ModelCatalog.recommend(12L * GIB, 20L * GIB));
        assertEquals(ModelCatalog.MAX, ModelCatalog.recommend(16L * GIB, 20L * GIB));
    }

    @Test
    public void recommendationDowngradesWhenStorageIsTight() {
        long onlyEdgeFits = ModelCatalog.requiredStorage(ModelCatalog.EDGE) + 1;
        assertEquals(ModelCatalog.EDGE, ModelCatalog.recommend(12L * GIB, onlyEdgeFits));
    }

    @Test
    public void catalogUsesPinnedHuggingFaceFiles() {
        for (ModelSpec model : ModelCatalog.all()) {
            assertTrue(model.downloadUrl().startsWith("https://huggingface.co/"));
            assertTrue(model.downloadUrl().contains("/resolve/" + model.revision + "/"));
            assertEquals(40, model.revision.length());
            assertTrue(model.fileName.endsWith(".gguf"));
            assertEquals(64, model.sha256.length());
        }
    }
}
