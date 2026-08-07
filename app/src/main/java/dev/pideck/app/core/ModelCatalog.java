package dev.pideck.app.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Single-source model catalog loaded from app/src/main/assets/models-v2.json. */
public final class ModelCatalog {
    public static final int SCHEMA_VERSION = 2;
    private static final long MIB = 1_048_576L;
    private static final long LOW_MEMORY_SAFETY_MIB = 1536L;
    private static volatile ModelCatalog current;

    private final String catalogVersion;
    private final List<ModelSpec> models;

    private ModelCatalog(String catalogVersion, List<ModelSpec> models) {
        this.catalogVersion = catalogVersion;
        this.models = Collections.unmodifiableList(new ArrayList<>(models));
    }

    public static synchronized ModelCatalog initialize(Context context) {
        if (current != null) return current;
        try (InputStream input = context.getAssets().open("models-v2.json")) {
            current = parse(readUtf8(input));
            return current;
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("Bundled model catalog is invalid", error);
        }
    }

    public static ModelCatalog parse(String raw) throws JSONException {
        JSONObject root = new JSONObject(raw);
        HashSet<String> rootKeys = new HashSet<>();
        Iterator<String> keys = root.keys();
        while (keys.hasNext()) rootKeys.add(keys.next());
        if (!rootKeys.equals(Set.of("schemaVersion", "catalogVersion", "models"))) {
            throw new JSONException("Unexpected model catalog fields");
        }
        if (root.getInt("schemaVersion") != SCHEMA_VERSION) {
            throw new JSONException("Unsupported model catalog schema");
        }
        String version = root.getString("catalogVersion");
        if (version.isBlank()) throw new JSONException("Catalog version is blank");
        JSONArray array = root.getJSONArray("models");
        if (array.length() == 0) throw new JSONException("Model catalog is empty");
        ArrayList<ModelSpec> models = new ArrayList<>();
        HashSet<String> ids = new HashSet<>();
        int defaults = 0;
        for (int i = 0; i < array.length(); i++) {
            ModelSpec model = ModelSpec.fromJson(array.getJSONObject(i));
            if (!ids.add(model.id)) throw new JSONException("Duplicate model id: " + model.id);
            if ("DEFAULT".equals(model.status)) defaults++;
            models.add(model);
        }
        if (defaults > 1) throw new JSONException("Only one DEFAULT model is allowed");
        return new ModelCatalog(version, models);
    }

    public static ModelCatalog get() {
        ModelCatalog value = current;
        if (value == null) {
            throw new IllegalStateException("ModelCatalog.initialize(context) was not called");
        }
        return value;
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public List<ModelSpec> all() {
        return models;
    }

    /** Unknown IDs are errors at the call site; there is deliberately no hidden fallback. */
    public Optional<ModelSpec> byId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        for (ModelSpec model : models) {
            if (model.id.equals(id)) return Optional.of(model);
        }
        return Optional.empty();
    }

    /**
     * Recommendation is explicit and based on current available memory, low-memory state, and
     * enough storage for incoming + private temporary copy + final artifact.
     *
     * <p>Memory is the only axis this can weigh, so a model that fits and is still a bad default
     * — Bonsai 27B fits a flagship and decodes at roughly one token per second — has to be kept
     * out by status. {@code CANDIDATE} means exactly that: listed and selectable by hand, never
     * proposed on the user's behalf.
     */
    public ModelSpec recommend(
            long availableMemoryBytes,
            boolean lowMemory,
            long freeStorageBytes
    ) {
        ModelSpec best = models.get(0);
        for (ModelSpec model : models) {
            if (!isRecommendable(model)) continue;
            long minimumMemory = model.minimumAvailableMiB * MIB;
            long estimated = model.estimatedPeakBytes();
            long safetyAdjusted = lowMemory
                    ? estimated + LOW_MEMORY_SAFETY_MIB * MIB
                    : estimated;
            if (availableMemoryBytes >= Math.max(minimumMemory, safetyAdjusted)
                    && freeStorageBytes >= requiredStorageForFreshInstall(model)) {
                best = model;
            }
        }
        return best;
    }

    /** A model the deck may propose by itself, as opposed to one the user has to pick. */
    public static boolean isRecommendable(ModelSpec model) {
        return !"BLOCKED".equals(model.status)
                && !"DEPRECATED".equals(model.status)
                && !"CANDIDATE".equals(model.status);
    }

    public static long requiredStorageForFreshInstall(ModelSpec model) {
        long safety = Math.max(512L * MIB, model.bytes / 5L);
        try {
            return Math.addExact(Math.multiplyExact(model.bytes, 3L), safety);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    /** Additional free bytes needed once the complete incoming artifact already exists. */
    public static long requiredStorageForPrivateInstall(ModelSpec model) {
        return model.bytes + Math.max(256L * MIB, model.bytes / 10L);
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
            if (output.size() > 2 * 1024 * 1024) {
                throw new IOException("Model catalog exceeds bounded size");
            }
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
