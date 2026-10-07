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
    public void recommendationStaysInsideMeasuredMobileLatencyEnvelope() {
        long storage = 100L * GIB;
        assertEquals("NANO", catalog.recommend(2500L * 1_048_576L, false, storage).tier);
        assertEquals("qwen3.5-2b", catalog.recommend(4L * GIB, false, storage).id);
        assertEquals("lfm2.5-2.6b-qad", catalog.recommend(6L * GIB, false, storage).id);
        assertEquals("lfm2.5-2.6b-qad", catalog.recommend(12L * GIB, false, storage).id);
    }

    @Test
    public void lowMemoryAndStoragePressureDownshiftExplicitly() {
        assertEquals(
                "NANO",
                catalog.recommend(4L * GIB, true, 100L * GIB).tier
        );
        assertEquals(
                "qwen3.5-2b",
                catalog.recommend(5L * GIB, true, 100L * GIB).id
        );
        assertEquals(
                "lfm2.5-2.6b-qad",
                catalog.recommend(6L * GIB, true, 100L * GIB).id
        );
        assertEquals(
                "lfm2.5-2.6b-qad",
                catalog.recommend(12L * GIB, true, 100L * GIB).id
        );
        ModelSpec edge = catalog.byId("qwen3.5-2b").orElseThrow();
        long onlyEdgeFits = ModelCatalog.requiredStorageForFreshInstall(edge) + 1;
        assertEquals("qwen3.5-2b", catalog.recommend(12L * GIB, false, onlyEdgeFits).id);

        ModelSpec nano = catalog.byId("qwen3.5-0.8b").orElseThrow();
        long onlyNanoFits = ModelCatalog.requiredStorageForFreshInstall(nano) + 1;
        assertEquals("NANO", catalog.recommend(12L * GIB, false, onlyNanoFits).tier);
    }

    @Test
    public void newModelsSelectTheirRuntimeWithoutAutomaticPromotion() {
        ModelSpec k2 = catalog.byId("k2-horizon-3.7b").orElseThrow();
        ModelSpec qwen = catalog.byId("qwen3.8-4b-distill").orElseThrow();
        assertEquals("k2horizon-35999d1-p2", k2.nativeRuntimeBuild());
        assertEquals("b10092", qwen.nativeRuntimeBuild());
        assertFalse(ModelCatalog.isRecommendable(k2));
        assertFalse(ModelCatalog.isRecommendable(qwen));
        CpuProfile cpu = CpuProfile.fromMaxFrequencies(new long[8]).forModel(k2);
        assertEquals(5, cpu.decodeThreads);
        assertEquals(5, cpu.batchThreads);
        assertFalse(cpu.strictAffinity);
        java.util.List<String> args = k2.nativeLlamaServerArguments("/private/model.gguf", cpu, 8080, "test-key");
        assertEquals("0", args.get(args.indexOf("--cpu-strict") + 1));
        assertEquals("low", args.get(args.indexOf("--reasoning-effort") + 1));
        assertEquals("0", args.get(args.indexOf("--poll") + 1));
        assertEquals("5", args.get(args.indexOf("-tb") + 1));
    }

    @Test
    public void explicitLargerSelectionRemainsAvailable() {
        ModelSpec max = catalog.byId("qwen3.5-9b").orElseThrow();
        assertEquals("MAX", max.tier);
        assertEquals("qwen3.5-9b", catalog.byId(max.id).orElseThrow().id);
        assertEquals(
                "lfm2.5-2.6b-qad",
                catalog.recommend(12L * GIB, false, 200L * GIB).id
        );
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
        assertFalse(ModelCatalog.isRecommendable(
                catalog.byId("ministral-3-3b-instruct-2512").orElseThrow()
        ));
        assertFalse(ModelCatalog.isRecommendable(
                catalog.byId("nanbeige4.2-3b").orElseThrow()
        ));
        assertFalse(ModelCatalog.isRecommendable(
                catalog.byId("qwen3.8-2b-distill").orElseThrow()
        ));
        assertFalse(ModelCatalog.isRecommendable(
                catalog.byId("granite-4.2-3b").orElseThrow()
        ));
        ModelSpec qad = catalog.byId("lfm2.5-2.6b-qad").orElseThrow();
        assertEquals("EXPERIMENTAL", qad.status);
        assertTrue(ModelCatalog.isRecommendable(qad));
        assertEquals("lfm2.5-2.6b-qad", catalog.recommend(12L * GIB, false, 200L * GIB).id);
    }

    @Test
    public void newAgentModelsUsePinnedArtifactsAndMatchingNativeRuntimes() {
        ModelSpec ministral = catalog.byId("ministral-3-3b-instruct-2512").orElseThrow();
        assertEquals("mistralai/Ministral-3-3B-Instruct-2512-GGUF", ministral.repo);
        assertEquals(2_147_023_008L, ministral.bytes);
        assertEquals(
                "9ed150d4367e68df0ac8e1540f6ddc65b42d0ee26378329d1ecbca60f93fc5f8",
                ministral.sha256
        );
        assertEquals("stock", ministral.serverFlavor);
        assertEquals("libpideck_llama_server.so", ministral.nativeServerLibraryName());
        assertEquals(
                ministral.nativeServerLibraryName(),
                NativeLlamaService.serverLibraryForFlavor(ministral.serverFlavor)
        );
        assertEquals("b10092", ministral.nativeRuntimeBuild());

        ModelSpec qwen38 = catalog.byId("qwen3.8-2b-distill").orElseThrow();
        assertEquals("empero-ai/Qwen3.8-2B-Distill-GGUF", qwen38.repo);
        assertEquals("f4f73582d0b149595450c719b9a7521a03894f9c", qwen38.revision);
        assertEquals("Qwen3.8-2B-Q4_K_M.gguf", qwen38.fileName);
        assertEquals(1_312_164_224L, qwen38.bytes);
        assertEquals(
                "4aa0fb13c431514262f259d420ecc95a8714df58ac2a2384514e20b93983f0ff",
                qwen38.sha256
        );
        assertEquals("on", qwen38.reasoningMode);
        assertEquals(
                List.of("--reasoning-budget", "256", "--cache-ram", "512", "--no-mmap"),
                qwen38.serverArgs
        );
        assertEquals("stock", qwen38.serverFlavor);
        assertEquals("b10092", qwen38.nativeRuntimeBuild());

        ModelSpec granite = catalog.byId("granite-4.2-3b").orElseThrow();
        assertEquals("ibm-granite/granite-4.2-3b-GGUF", granite.repo);
        assertEquals("47a3d9699d7539606c83943d717fcea7bd9f6a19", granite.revision);
        assertEquals("granite-4.2-3b-Q4_K_M.gguf", granite.fileName);
        assertEquals(2_244_012_160L, granite.bytes);
        assertEquals(
                "20e436143017578687f7f848225cc6c6038126c84149192229c7dff6e4e0f427",
                granite.sha256
        );
        assertEquals("on", granite.reasoningMode);
        assertEquals(
                List.of("--reasoning-budget", "256", "--cache-ram", "512", "--no-mmap"),
                granite.serverArgs
        );
        assertEquals("stock", granite.serverFlavor);
        assertEquals("b10092", granite.nativeRuntimeBuild());

        ModelSpec qad = catalog.byId("lfm2.5-2.6b-qad").orElseThrow();
        assertEquals("LiquidAI/LFM2.5-2.6B-GGUF", qad.repo);
        assertEquals("f4a289c8a200a5ca71005ba7abc2dad33058a450", qad.revision);
        assertEquals("LFM2.5-2.6B-QAD-Q4_0.gguf", qad.fileName);
        assertEquals(1_593_894_944L, qad.bytes);
        assertEquals(
                "a247afd6414918eac8e520a9e6137dc271235461ecbe1180462221d5b8d40b03",
                qad.sha256
        );
        assertEquals("on", qad.reasoningMode);
        assertEquals(
                List.of("--repeat-penalty", "1.1", "--reasoning-budget", "256", "--cache-ram", "512",
                        "--no-mmap"),
                qad.serverArgs
        );
        assertEquals("stock", qad.serverFlavor);
        assertEquals("b10092", qad.nativeRuntimeBuild());

        ModelSpec nanbeige = catalog.byId("nanbeige4.2-3b").orElseThrow();
        assertEquals("owao/Nanbeige4.2-3B-GGUF", nanbeige.repo);
        assertEquals(2_574_807_904L, nanbeige.bytes);
        assertEquals(
                "ffe1b9b8ee95ec4b962c379905aa8be6f72ae9c4645c6c70e3b6ff7b197e6ef4",
                nanbeige.sha256
        );
        assertEquals("nanbeige42", nanbeige.serverFlavor);
        assertEquals("libpideck_nanbeige_server.so", nanbeige.nativeServerLibraryName());
        assertEquals(
                nanbeige.nativeServerLibraryName(),
                NativeLlamaService.serverLibraryForFlavor(nanbeige.serverFlavor)
        );
        assertEquals("nanbeige42-c6640a1", nanbeige.nativeRuntimeBuild());
    }

    @Test
    public void unknownModelIdNeverFallsBack() {
        assertTrue(catalog.byId("deleted-model-id").isEmpty());
        assertFalse(catalog.byId(null).isPresent());
    }

    @Test
    public void bonsai2UsesItsPrismRuntimeAndExactLocalArtifact() {
        ModelSpec model = catalog.byId("bonsai2-27b").orElseThrow();
        assertEquals(5_946_648_928L, model.bytes);
        assertEquals(
                "53107f530aa52eb00912263ab1ee29bd199261c87cd7b4ad4ca1318c1fe33ee3",
                model.sha256
        );
        assertEquals("prism-842b188", model.nativeRuntimeBuild());
        assertEquals("libpideck_prism_server.so", model.nativeServerLibraryName());
        assertEquals(model.nativeServerLibraryName(),
                NativeLlamaService.serverLibraryForFlavor(model.serverFlavor));
        assertFalse(ModelCatalog.isRecommendable(model));
        List<String> args = model.nativeLlamaServerArguments(
                "/private/bonsai2.gguf", edgeProfile(), 8080, "test-key"
        );
        assertEquals("off", args.get(args.indexOf("--reasoning") + 1));
        assertEquals("0", args.get(args.indexOf("--reasoning-budget") + 1));
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
            assertTrue(
                    List.of("Apache-2.0", "MIT", "LicenseRef-LFM-Open-1.0")
                            .contains(model.licenseSpdx)
            );
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
        assertEquals("off", catalog.byId("qwen3.5-2b").orElseThrow().reasoningMode);
        assertEquals("on", core.reasoningMode);
        assertEquals("512", coreArgs.get(coreArgs.indexOf("--reasoning-budget") + 1));
        assertEquals(0.6, core.temperature, 0.0001);
        assertEquals(0.95, core.topP, 0.0001);
        assertEquals(0.0, core.presencePenalty, 0.0001);
        assertEquals(2048, core.maxTokens);
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
    public void unknownNativeServerFlavorIsRejected() throws Exception {
        String raw = readUtf8(asset("models-v2.json"));
        ModelCatalog.parse(raw.replaceFirst(
                "\"serverFlavor\": \"stock\"",
                "\"serverFlavor\": \"floating-fork\""
        ));
    }

    @Test
    public void onlyTheVerifiedRuntimeSavesPrefixSnapshots() throws Exception {
        ModelCatalog catalog = ModelCatalog.parse(readUtf8(asset("models-v2.json")));
        ModelSpec stock = catalog.byId("lfm2.5-2.6b-qad").orElseThrow();
        List<String> args = stock.nativeLlamaServerArguments(
                "/private/m.gguf", edgeProfile(), 8080, "key", "/cache/llama-slots"
        );
        int index = args.indexOf("--slot-save-path");
        assertEquals("/cache/llama-slots/", args.get(index + 1));
        assertEquals(-1, stock.nativeLlamaServerArguments(
                "/private/m.gguf", edgeProfile(), 8080, "key"
        ).indexOf("--slot-save-path"));
        ModelSpec prism = catalog.byId("bonsai2-27b").orElseThrow();
        assertEquals(-1, prism.nativeLlamaServerArguments(
                "/private/b.gguf", edgeProfile(), 8080, "key", "/cache/llama-slots"
        ).indexOf("--slot-save-path"));
    }

    @Test(expected = JSONException.class)
    public void catalogCannotChooseWhereSlotsAreWritten() throws Exception {
        String raw = readUtf8(asset("models-v2.json"));
        ModelCatalog.parse(raw.replaceFirst(
                "\"serverArgs\": \\[",
                "\"serverArgs\": [\"--slot-save-path\", \"/sdcard/\", "
        ));
    }

    @Test(expected = JSONException.class)
    public void modelCannotOverrideManagedLoopbackArguments() throws Exception {
        String raw = readUtf8(asset("models-v2.json"));
        ModelCatalog.parse(raw.replaceFirst(
                "\"serverArgs\": \\[",
                "\"serverArgs\": [\"--host\", \"0.0.0.0\", "
        ));
    }

    @Test(expected = JSONException.class)
    public void outputBudgetMustFitInsideRecommendedContext() throws Exception {
        String raw = readUtf8(asset("models-v2.json"));
        ModelCatalog.parse(raw.replaceFirst(
                "\"maxTokens\": 1024",
                "\"maxTokens\": 4096"
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
