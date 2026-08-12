package dev.pideck.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, strictly parsed entry from the bundled models-v2.json catalog. */
public final class ModelSpec {
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9._-]+$");
    private static final Pattern REPOSITORY =
            Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern ARTIFACT =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]*\\.gguf$");
    private static final Pattern REVISION = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> TIERS = Set.of("NANO", "EDGE", "CORE", "MAX");
    private static final Set<String> STATUSES = Set.of(
            "DEFAULT", "SUPPORTED", "CANDIDATE", "EXPERIMENTAL", "DEPRECATED", "BLOCKED"
    );
    private static final Set<String> SERVER_FLAVORS = Set.of("stock", "nanbeige42");
    // LicenseRef-LFM-Open-1.0: LFM Open License v1.0, reviewed 2026-08-07 — Apache-2.0-derived,
    // full use below a $10M annual-revenue threshold; see docs/model-admission.md.
    private static final Set<String> LICENSES =
            Set.of("Apache-2.0", "MIT", "LicenseRef-LFM-Open-1.0");
    private static final Set<String> MANAGED_SERVER_FLAGS = Set.of(
            "-m", "--model", "--alias", "--host", "--port",
            "-c", "--ctx-size", "-np", "--parallel", "-t", "--threads",
            "-tb", "--threads-batch", "-Cr", "--cpu-range", "--cpu-strict",
            "-Crb", "--cpu-range-batch", "--cpu-strict-batch",
            "--jinja", "--reasoning", "--temp", "--top-p", "--top-k",
            "--min-p", "--presence-penalty", "--api-key",
            "--spec-type", "--spec-draft", "--spec-draft-n",
            "--spec-draft-n-min", "--spec-draft-n-max",
            "--spec-ngram-mod-n-max", "--spec-ngram-mod-n-min",
            "--spec-ngram-mod-n-match", "--spec-ngram-simple-size-n",
            "--spec-ngram-simple-size-m", "--spec-ngram-simple-min-hits",
            "--model-draft", "-md"
    );
    /**
     * Only self-speculation is admissible. Every mode here drafts from the model already
     * loaded — MTP from its own prediction heads, ngram from the live context — so none of
     * them introduces a second set of weights the memory guard has not accounted for.
     */
    private static final Set<String> SPECULATIVE_MODES = Set.of(
            "off", "draft-mtp", "ngram-mod", "ngram-simple"
    );

    public final String id;
    public final String title;
    public final String tier;
    public final String status;
    public final String note;
    public final String licenseSpdx;
    public final String licenseUrl;
    public final String repo;
    public final String revision;
    public final String provenance;
    public final String fileName;
    public final long bytes;
    public final String sha256;
    public final String serverFlavor;
    public final String minimumLlamaCppVersion;
    public final int recommendedContext;
    public final int maximumTestedContext;
    public final int parallelSlots;
    public final boolean requiresJinja;
    public final List<String> serverArgs;
    public final String chatTemplateMode;
    public final String reasoningMode;
    public final String speculativeMode;
    public final int speculativeDraftMax;
    public final double temperature;
    public final double topP;
    public final int topK;
    public final double minP;
    public final double presencePenalty;
    public final String toolProtocol;
    public final String piProfile;
    public final int maxTokens;
    public final int minimumAvailableMiB;
    public final Integer measuredPeakRssMiB;

    private ModelSpec(
            String id,
            String title,
            String tier,
            String status,
            String note,
            String licenseSpdx,
            String licenseUrl,
            String repo,
            String revision,
            String provenance,
            String fileName,
            long bytes,
            String sha256,
            String serverFlavor,
            String minimumLlamaCppVersion,
            int recommendedContext,
            int maximumTestedContext,
            int parallelSlots,
            boolean requiresJinja,
            List<String> serverArgs,
            String chatTemplateMode,
            String reasoningMode,
            String speculativeMode,
            int speculativeDraftMax,
            double temperature,
            double topP,
            int topK,
            double minP,
            double presencePenalty,
            String toolProtocol,
            String piProfile,
            int maxTokens,
            int minimumAvailableMiB,
            Integer measuredPeakRssMiB
    ) {
        this.id = id;
        this.title = title;
        this.tier = tier;
        this.status = status;
        this.note = note;
        this.licenseSpdx = licenseSpdx;
        this.licenseUrl = licenseUrl;
        this.repo = repo;
        this.revision = revision;
        this.provenance = provenance;
        this.fileName = fileName;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.serverFlavor = serverFlavor;
        this.minimumLlamaCppVersion = minimumLlamaCppVersion;
        this.recommendedContext = recommendedContext;
        this.maximumTestedContext = maximumTestedContext;
        this.parallelSlots = parallelSlots;
        this.requiresJinja = requiresJinja;
        this.serverArgs = Collections.unmodifiableList(new ArrayList<>(serverArgs));
        this.chatTemplateMode = chatTemplateMode;
        this.reasoningMode = reasoningMode;
        this.speculativeMode = speculativeMode;
        this.speculativeDraftMax = speculativeDraftMax;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.minP = minP;
        this.presencePenalty = presencePenalty;
        this.toolProtocol = toolProtocol;
        this.piProfile = piProfile;
        this.maxTokens = maxTokens;
        this.minimumAvailableMiB = minimumAvailableMiB;
        this.measuredPeakRssMiB = measuredPeakRssMiB;
    }

    static ModelSpec fromJson(JSONObject value) throws JSONException {
        requireKeys(value, Set.of(
                "id", "title", "tier", "status", "note", "license", "source",
                "artifact", "runtime", "sampling", "agent", "memory", "benchmark"
        ));
        String id = required(value, "id");
        if (id.length() > 128 || !ID.matcher(id).matches()) {
            throw new JSONException("Invalid model id: " + id);
        }
        String tier = required(value, "tier");
        if (!TIERS.contains(tier)) throw new JSONException("Invalid model tier: " + tier);
        String status = required(value, "status");
        if (!STATUSES.contains(status)) throw new JSONException("Invalid model status: " + status);

        JSONObject license = value.getJSONObject("license");
        requireKeys(license, Set.of("spdx", "weightsUrl", "verifiedAt"));
        String spdx = required(license, "spdx");
        if (!LICENSES.contains(spdx)) throw new JSONException("Unapproved model license: " + spdx);
        String weightsUrl = required(license, "weightsUrl");
        if (!weightsUrl.startsWith("https://")) throw new JSONException("Model license URL must use HTTPS");

        JSONObject source = value.getJSONObject("source");
        requireKeys(source, Set.of(
                "repository", "revision", "official", "provenance",
                "provenanceStatus", "architecture", "upstreamModel", "conversion"
        ));
        String revision = required(source, "revision");
        if (!REVISION.matcher(revision).matches()) {
            throw new JSONException("Model revision is not an immutable 40-hex commit");
        }
        String provenanceStatus = required(source, "provenanceStatus");
        if (!Set.of("VERIFIED", "INCOMPLETE").contains(provenanceStatus)) {
            throw new JSONException("Invalid model provenance status");
        }
        JSONObject conversion = source.getJSONObject("conversion");
        requireKeys(conversion, Set.of(
                "performedBy", "tool", "toolRevision", "upstreamRevision",
                "quantizationCommand", "buildEnvironment", "licenseChain"
        ));

        JSONObject artifact = value.getJSONObject("artifact");
        requireKeys(artifact, Set.of("file", "bytes", "sha256"));
        String file = required(artifact, "file");
        if (!ARTIFACT.matcher(file).matches()) {
            throw new JSONException("Artifact must be a safe GGUF basename");
        }
        String repository = required(source, "repository");
        if (!REPOSITORY.matcher(repository).matches()) {
            throw new JSONException("Model repository must be owner/name");
        }
        long bytes = artifact.getLong("bytes");
        if (bytes <= 0) throw new JSONException("Artifact bytes must be positive");
        String sha256 = required(artifact, "sha256");
        if (!SHA256.matcher(sha256).matches()) throw new JSONException("Invalid artifact SHA-256");

        JSONObject runtime = value.getJSONObject("runtime");
        requireKeys(runtime, Set.of(
                "serverFlavor", "minimumLlamaCppVersion",
                "recommendedContext", "maximumTestedContext",
                "parallelSlots", "requiresJinja", "serverArgs",
                "chatTemplateMode", "reasoningMode", "speculative"
        ));
        String serverFlavor = required(runtime, "serverFlavor");
        if (!SERVER_FLAVORS.contains(serverFlavor)) {
            throw new JSONException("Unsupported native server flavor: " + serverFlavor);
        }
        String minimumLlamaCppVersion = required(runtime, "minimumLlamaCppVersion");
        String expectedRuntimeBuild = "nanbeige42".equals(serverFlavor)
                ? "nanbeige42-c6640a1"
                : "b10092";
        if (!expectedRuntimeBuild.equals(minimumLlamaCppVersion)) {
            throw new JSONException("Native server flavor and minimum runtime disagree");
        }
        int recommendedContext = positive(runtime, "recommendedContext");
        int maximumContext = positive(runtime, "maximumTestedContext");
        if (recommendedContext > maximumContext) {
            throw new JSONException("Recommended context exceeds tested context");
        }
        JSONArray rawArgs = runtime.getJSONArray("serverArgs");
        if (rawArgs.length() > 32) throw new JSONException("Too many model server arguments");
        ArrayList<String> serverArgs = new ArrayList<>();
        for (int i = 0; i < rawArgs.length(); i++) {
            String argument = rawArgs.getString(i);
            if (argument.indexOf('\0') >= 0 || argument.length() > 512) {
                throw new JSONException("Unsafe model server argument");
            }
            String flag = argument.contains("=")
                    ? argument.substring(0, argument.indexOf('='))
                    : argument;
            if (MANAGED_SERVER_FLAGS.contains(flag)) {
                throw new JSONException("serverArgs overrides a managed flag: " + flag);
            }
            serverArgs.add(argument);
        }
        String templateMode = required(runtime, "chatTemplateMode");
        if (!Set.of("embedded", "explicit").contains(templateMode)) {
            throw new JSONException("Unsupported chat template mode");
        }
        String reasoningMode = required(runtime, "reasoningMode");
        if (!Set.of("off", "on", "model-default").contains(reasoningMode)) {
            throw new JSONException("Unsupported reasoning mode");
        }

        JSONObject speculation = runtime.getJSONObject("speculative");
        requireKeys(speculation, Set.of("mode", "draftMax"));
        String speculativeMode = required(speculation, "mode");
        if (!SPECULATIVE_MODES.contains(speculativeMode)) {
            throw new JSONException("Unsupported speculative mode: " + speculativeMode);
        }
        int speculativeDraftMax = speculation.getInt("draftMax");
        if (speculativeDraftMax < 0 || speculativeDraftMax > 64) {
            throw new JSONException("Speculative draft budget is out of range");
        }
        if ("off".equals(speculativeMode) != (speculativeDraftMax == 0)) {
            throw new JSONException(
                    "Speculative mode and draft budget disagree: " + speculativeMode
            );
        }

        JSONObject sampling = value.getJSONObject("sampling");
        requireKeys(sampling, Set.of(
                "temperature", "topP", "topK", "minP", "presencePenalty"
        ));
        JSONObject agent = value.getJSONObject("agent");
        requireKeys(agent, Set.of(
                "toolProtocol", "piProfile", "maxTokens",
                "supportsToolCalls", "supportsMultiTurnTools"
        ));
        int maxTokens = positive(agent, "maxTokens");
        if (maxTokens >= recommendedContext) {
            throw new JSONException("Model output budget must fit inside recommended context");
        }
        if (!agent.getBoolean("supportsToolCalls")
                || !agent.getBoolean("supportsMultiTurnTools")) {
            throw new JSONException("Catalog model does not satisfy the Pi tool contract");
        }

        JSONObject memory = value.getJSONObject("memory");
        requireKeys(memory, Set.of(
                "minimumAvailableMiB", "measuredPeakRssMiB", "deviceClass"
        ));
        Integer measured = memory.isNull("measuredPeakRssMiB")
                ? null
                : positive(memory, "measuredPeakRssMiB");

        return new ModelSpec(
                id,
                required(value, "title"),
                tier,
                status,
                value.getString("note"),
                spdx,
                weightsUrl,
                repository,
                revision,
                required(source, "provenance"),
                file,
                bytes,
                sha256,
                serverFlavor,
                minimumLlamaCppVersion,
                recommendedContext,
                maximumContext,
                positive(runtime, "parallelSlots"),
                runtime.getBoolean("requiresJinja"),
                serverArgs,
                templateMode,
                reasoningMode,
                speculativeMode,
                speculativeDraftMax,
                sampling.getDouble("temperature"),
                sampling.getDouble("topP"),
                sampling.getInt("topK"),
                sampling.getDouble("minP"),
                sampling.getDouble("presencePenalty"),
                required(agent, "toolProtocol"),
                required(agent, "piProfile"),
                maxTokens,
                positive(memory, "minimumAvailableMiB"),
                measured
        );
    }

    public String downloadUrl() {
        return "https://huggingface.co/" + repo + "/resolve/" + revision + "/"
                + fileName + "?download=true";
    }

    public String humanSize() {
        return humanBytes(bytes);
    }

    /** Returns the only packaged executable this catalog entry may launch. */
    public String nativeServerLibraryName() {
        switch (serverFlavor) {
            case "stock":
                return "libpideck_llama_server.so";
            case "nanbeige42":
                return "libpideck_nanbeige_server.so";
            default:
                throw new IllegalStateException("Unsupported native server flavor: " + serverFlavor);
        }
    }

    /** Exact runtime identity sent to the durable Termux-side adoption contract. */
    public String nativeRuntimeBuild() {
        switch (serverFlavor) {
            case "stock":
                return "b10092";
            case "nanbeige42":
                return "nanbeige42-c6640a1";
            default:
                throw new IllegalStateException("Unsupported native server flavor: " + serverFlavor);
        }
    }

    /**
     * Renders a length the way the model rows already read. A hand-picked file can be any size, so
     * this keeps going below a mebibyte rather than rounding a small file away to "0 MiB", which
     * would say nothing about what the user actually chose.
     */
    public static String humanBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1_024L) return bytes + " B";
        if (bytes < 1_048_576L) {
            return String.format(Locale.US, "%.1f KiB", bytes / 1_024.0);
        }
        double gib = bytes / 1_073_741_824.0;
        if (gib < 1.0) {
            return String.format(Locale.US, "%.0f MiB", bytes / 1_048_576.0);
        }
        return String.format(Locale.US, "%.2f GiB", gib);
    }

    /**
     * Effective llama-server arguments. Every value is an array element; no shell concatenation.
     */
    public List<String> llamaServerArguments(
            String privateModelPath,
            int threads,
            int port,
            String apiKey
    ) {
        return llamaServerArguments(privateModelPath, threads, null, port, apiKey);
    }

    public List<String> nativeLlamaServerArguments(
            String privateModelPath,
            CpuProfile profile,
            int port,
            String apiKey
    ) {
        if (profile == null) throw new IllegalArgumentException("CPU profile is required");
        return llamaServerArguments(
                privateModelPath,
                profile.decodeThreads,
                profile,
                port,
                apiKey
        );
    }

    private List<String> llamaServerArguments(
            String privateModelPath,
            int threads,
            CpuProfile profile,
            int port,
            String apiKey
    ) {
        ArrayList<String> args = new ArrayList<>();
        Collections.addAll(
                args,
                "-m", privateModelPath,
                "--alias", id,
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "-c", Integer.toString(recommendedContext),
                "-np", Integer.toString(parallelSlots),
                "-t", Integer.toString(Math.max(2, Math.min(8, threads)))
        );
        if (profile != null) {
            Collections.addAll(
                    args,
                    "-tb", Integer.toString(profile.batchThreads),
                    "-Cr", profile.decodeCpuSet,
                    "--cpu-strict", "1",
                    "-Crb", profile.batchCpuSet,
                    "--cpu-strict-batch", "1"
            );
        }
        if (requiresJinja) args.add("--jinja");
        Collections.addAll(args, speculativeArguments());
        if (!"model-default".equals(reasoningMode)) {
            Collections.addAll(args, "--reasoning", reasoningMode);
        }
        Collections.addAll(
                args,
                "--temp", decimal(temperature),
                "--top-p", decimal(topP),
                "--top-k", Integer.toString(topK),
                "--min-p", decimal(minP),
                "--presence-penalty", decimal(presencePenalty)
        );
        args.addAll(serverArgs);
        if (apiKey != null && !apiKey.isBlank()) {
            Collections.addAll(args, "--api-key", apiKey);
        }
        return Collections.unmodifiableList(args);
    }

    /**
     * llama.cpp spells the draft budget differently per family: the draft-model path reads
     * {@code --spec-draft-n-max} while the ngram path reads {@code --spec-ngram-mod-n-max}.
     * A model that declares no speculation contributes no arguments at all, which keeps the
     * measured command line for existing profiles byte-identical.
     */
    private String[] speculativeArguments() {
        switch (speculativeMode) {
            case "off":
                return new String[0];
            case "draft-mtp":
                return new String[]{
                        "--spec-type", "draft-mtp",
                        "--spec-draft-n-max", Integer.toString(speculativeDraftMax)
                };
            case "ngram-mod":
                return new String[]{
                        "--spec-type", "ngram-mod",
                        "--spec-ngram-mod-n-max", Integer.toString(speculativeDraftMax)
                };
            case "ngram-simple":
                return new String[]{
                        "--spec-type", "ngram-simple",
                        "--spec-draft-n-max", Integer.toString(speculativeDraftMax)
                };
            default:
                throw new IllegalStateException("Unsupported speculative mode: " + speculativeMode);
        }
    }

    public long estimatedPeakBytes() {
        if (measuredPeakRssMiB != null) return measuredPeakRssMiB * 1_048_576L;
        long weightsAndRuntime = bytes + Math.max(512L * 1_048_576L, bytes / 4L);
        long kvAndContext = (long) recommendedContext * 256L * 1024L;
        return weightsAndRuntime + kvAndContext;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModelSpec && id.equals(((ModelSpec) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    private static String decimal(double value) {
        return String.format(Locale.US, "%.4f", value)
                .replaceAll("0+$", "")
                .replaceAll("\\.$", ".0");
    }

    private static String required(JSONObject value, String key) throws JSONException {
        String result = value.getString(key);
        if (result.isBlank()) throw new JSONException("Blank required field: " + key);
        return result;
    }

    private static int positive(JSONObject value, String key) throws JSONException {
        int result = value.getInt(key);
        if (result <= 0) throw new JSONException("Field must be positive: " + key);
        return result;
    }

    private static void requireKeys(JSONObject value, Set<String> expected) throws JSONException {
        Set<String> actual = new java.util.HashSet<>();
        Iterator<String> keys = value.keys();
        while (keys.hasNext()) actual.add(keys.next());
        if (!actual.equals(expected)) {
            throw new JSONException(
                    "Unexpected or missing fields. Expected " + expected + ", got " + actual
            );
        }
    }
}
