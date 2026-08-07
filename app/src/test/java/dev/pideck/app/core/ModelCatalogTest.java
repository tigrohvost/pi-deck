package dev.pideck.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ModelCatalogTest {
    private static final long GIB = 1_073_741_824L;
    private static ModelCatalog catalog;

    @BeforeClass
    public static void loadCatalog() throws Exception {
        catalog = ModelCatalog.parse(readUtf8(asset("models-v2.json")));
    }

    @Test
    public void recommendationUsesAvailableMemoryAndStorage() {
        long storage = 100L * GIB;
        assertEquals("NANO", catalog.recommend(2500L * 1_048_576L, false, storage).tier);
        assertEquals("EDGE", catalog.recommend(4L * GIB, false, storage).tier);
        assertEquals("CORE", catalog.recommend(6L * GIB, false, storage).tier);
        assertEquals("MAX", catalog.recommend(10L * GIB, false, storage).tier);
    }

    @Test
    public void lowMemoryAndStoragePressureDownshiftExplicitly() {
        assertEquals(
                "NANO",
                catalog.recommend(4L * GIB, true, 100L * GIB).tier
        );
        ModelSpec edge = catalog.byId("qwen3.5-2b").orElseThrow();
        long onlyEdgeFits = ModelCatalog.requiredStorageForFreshInstall(edge) + 1;
        assertEquals("EDGE", catalog.recommend(12L * GIB, false, onlyEdgeFits).tier);
    }

    /**
     * Bonsai 27B fits a flagship's memory and decodes at roughly one token per second, which the
     * memory-based recommendation cannot see. CANDIDATE is what keeps it out of it.
     */
    @Test
    public void candidatesAreListedButNeverRecommended() {
        ModelSpec bonsai = catalog.byId("bonsai-27b").orElseThrow();
        assertEquals("CANDIDATE", bonsai.status);
        assertFalse(ModelCatalog.isRecommendable(bonsai));
        assertEquals("qwen3.5-9b", catalog.recommend(12L * GIB, false, 200L * GIB).id);
    }

    @Test
    public void unknownModelIdNeverFallsBack() {
        assertTrue(catalog.byId("deleted-model-id").isEmpty());
        assertFalse(catalog.byId(null).isPresent());
    }

    @Test
    public void catalogUsesPinnedArtifactsAndAllowlistedLicenses() {
        assertEquals(2, ModelCatalog.SCHEMA_VERSION);
        for (ModelSpec model : catalog.all()) {
            assertTrue(model.downloadUrl().startsWith("https://huggingface.co/"));
            assertTrue(model.downloadUrl().contains("/resolve/" + model.revision + "/"));
            assertEquals(40, model.revision.length());
            assertTrue(model.fileName.endsWith(".gguf"));
            assertEquals(64, model.sha256.length());
            assertTrue(List.of("Apache-2.0", "MIT").contains(model.licenseSpdx));
            // No model is promoted without a checked-in benchmark report.
            assertTrue(List.of("CANDIDATE", "EXPERIMENTAL").contains(model.status));
        }
    }

    @Test
    public void effectiveArgumentsAreModelSpecificAndArrayBased() {
        ModelSpec nano = catalog.byId("qwen3.5-0.8b").orElseThrow();
        ModelSpec core = catalog.byId("qwen3.5-4b").orElseThrow();
        List<String> nanoArgs = nano.llamaServerArguments(
                "/private/nano.gguf", 99, 8080, "secret"
        );
        List<String> coreArgs = core.llamaServerArguments(
                "/private/core.gguf", 4, 8080, "secret"
        );
        assertEquals("/private/nano.gguf", nanoArgs.get(nanoArgs.indexOf("-m") + 1));
        assertEquals("8", nanoArgs.get(nanoArgs.indexOf("-t") + 1));
        assertEquals(
                Integer.toString(nano.recommendedContext),
                nanoArgs.get(nanoArgs.indexOf("-c") + 1)
        );
        assertEquals(
                Integer.toString(core.recommendedContext),
                coreArgs.get(coreArgs.indexOf("-c") + 1)
        );
        assertTrue(nanoArgs.contains("--api-key"));
        assertFalse(nanoArgs.contains("0.0.0.0"));
    }

    @Test
    public void nativeArgumentsPinMeasuredAffinityAndNeverEnableSpeculativeMtp() {
        ModelSpec edge = catalog.byId("qwen3.5-2b").orElseThrow();
        assertEquals(10240, edge.recommendedContext);
        CpuProfile profile = CpuProfile.fromMaxFrequencies(new long[]{
                2_016_000, 2_016_000, 2_016_000,
                2_803_000, 2_803_000, 2_803_000, 2_803_000,
                3_360_000
        });
        List<String> args = edge.nativeLlamaServerArguments(
                "/private/edge.gguf", profile, 8080, "secret"
        );
        assertEquals("5", args.get(args.indexOf("-t") + 1));
        assertEquals("8", args.get(args.indexOf("-tb") + 1));
        assertEquals("10240", args.get(args.indexOf("-c") + 1));
        assertEquals("3-7", args.get(args.indexOf("-Cr") + 1));
        assertEquals("0-7", args.get(args.indexOf("-Crb") + 1));
        assertFalse(args.contains("--spec-type"));
        assertFalse(args.contains("--spec-draft"));
    }

    @Test
    public void declaredMtpSpeculationReachesTheServerCommandLine() throws Exception {
        ModelCatalog mtp = ModelCatalog.parse(withSpeculative(
                "{\"mode\": \"draft-mtp\", \"draftMax\": 4}"
        ));
        List<String> args = mtp.byId("qwen3.5-2b").orElseThrow()
                .nativeLlamaServerArguments("/private/edge.gguf", edgeProfile(), 8080, "secret");
        assertEquals("draft-mtp", args.get(args.indexOf("--spec-type") + 1));
        assertEquals("4", args.get(args.indexOf("--spec-draft-n-max") + 1));
    }

    @Test
    public void declaredNgramSpeculationNeedsNoDraftModel() throws Exception {
        ModelCatalog ngram = ModelCatalog.parse(withSpeculative(
                "{\"mode\": \"ngram-mod\", \"draftMax\": 16}"
        ));
        List<String> args = ngram.byId("qwen3.5-2b").orElseThrow()
                .nativeLlamaServerArguments("/private/edge.gguf", edgeProfile(), 8080, "secret");
        assertEquals("ngram-mod", args.get(args.indexOf("--spec-type") + 1));
        assertEquals("16", args.get(args.indexOf("--spec-ngram-mod-n-max") + 1));
        assertFalse(args.contains("--model-draft"));
    }

    @Test(expected = JSONException.class)
    public void unknownSpeculativeModeIsRejected() throws Exception {
        ModelCatalog.parse(withSpeculative("{\"mode\": \"draft-eagle3\", \"draftMax\": 4}"));
    }

    @Test(expected = JSONException.class)
    public void speculationWithoutADraftBudgetIsRejected() throws Exception {
        ModelCatalog.parse(withSpeculative("{\"mode\": \"draft-mtp\", \"draftMax\": 0}"));
    }

    @Test(expected = JSONException.class)
    public void unknownCriticalCatalogFieldIsRejected() throws Exception {
        String raw = readUtf8(asset("models-v2.json"));
        ModelCatalog.parse(raw.replaceFirst(
                "\"catalogVersion\"",
                "\"unexpected\":true,\"catalogVersion\""
        ));
    }

    @Test(expected = JSONException.class)
    public void modelCannotOverrideManagedLoopbackArguments() throws Exception {
        String raw = readUtf8(asset("models-v2.json"));
        ModelCatalog.parse(raw.replaceFirst(
                "\"serverArgs\": \\[\\]",
                "\"serverArgs\": [\"--host\", \"0.0.0.0\"]"
        ));
    }

    /**
     * Rewrites the EDGE entry's speculative block. The first occurrence belongs to the NANO
     * model, so the replacement targets the second one and leaves every other entry alone.
     */
    private static String withSpeculative(String block) throws Exception {
        String raw = readUtf8(asset("models-v2.json"));
        String original = "\"speculative\": {\n"
                + "          \"mode\": \"off\",\n"
                + "          \"draftMax\": 0\n"
                + "        }";
        int first = raw.indexOf(original);
        int second = raw.indexOf(original, first + 1);
        if (second < 0) throw new IllegalStateException("Catalog has no second speculative block");
        return raw.substring(0, second)
                + "\"speculative\": " + block
                + raw.substring(second + original.length());
    }

    private static CpuProfile edgeProfile() {
        return CpuProfile.fromMaxFrequencies(new long[]{
                2_016_000, 2_016_000, 2_016_000,
                2_803_000, 2_803_000, 2_803_000, 2_803_000,
                3_360_000
        });
    }

    private static Path asset(String name) {
        Path module = Path.of("src/main/assets", name);
        return Files.exists(module) ? module : Path.of("app/src/main/assets", name);
    }

    private static String readUtf8(Path path) throws Exception {
        return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }
}
