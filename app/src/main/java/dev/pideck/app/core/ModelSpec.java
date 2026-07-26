package dev.pideck.app.core;

import java.util.Locale;

public final class ModelSpec {
    public final String id;
    public final String title;
    public final String tier;
    public final String repo;
    public final String revision;
    public final String fileName;
    public final long bytes;
    public final String sha256;
    public final int minimumRamGb;
    public final String note;

    public ModelSpec(
            String id,
            String title,
            String tier,
            String repo,
            String revision,
            String fileName,
            long bytes,
            String sha256,
            int minimumRamGb,
            String note
    ) {
        this.id = id;
        this.title = title;
        this.tier = tier;
        this.repo = repo;
        this.revision = revision;
        this.fileName = fileName;
        this.bytes = bytes;
        this.sha256 = sha256;
        this.minimumRamGb = minimumRamGb;
        this.note = note;
    }

    public String downloadUrl() {
        return "https://huggingface.co/" + repo + "/resolve/" + revision + "/"
                + fileName + "?download=true";
    }

    public String humanSize() {
        double gib = bytes / 1_073_741_824.0;
        if (gib < 1.0) {
            return String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0);
        }
        return String.format(Locale.US, "%.2f GB", gib);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ModelSpec && id.equals(((ModelSpec) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
