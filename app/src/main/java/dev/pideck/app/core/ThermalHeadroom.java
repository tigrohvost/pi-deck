package dev.pideck.app.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Fraction of the big core's nominal clock the thermal governor still allows.
 * Same definition as tools/speculative_probe.py: scaling_max_freq / cpuinfo_max_freq
 * on cpu7. Any read or parse failure returns null and the caller stays silent —
 * a thermal indicator must never itself break dispatch.
 */
public final class ThermalHeadroom {

    private static final Path BASE = Paths.get("/sys/devices/system/cpu/cpu7/cpufreq");

    private ThermalHeadroom() {}

    public static Float read() {
        try {
            return parse(
                    new String(
                            Files.readAllBytes(BASE.resolve("scaling_max_freq")),
                            StandardCharsets.UTF_8
                    ),
                    new String(
                            Files.readAllBytes(BASE.resolve("cpuinfo_max_freq")),
                            StandardCharsets.UTF_8
                    )
            );
        } catch (Exception error) {
            return null;
        }
    }

    public static Float parse(String scalingMaxRaw, String cpuinfoMaxRaw) {
        if (scalingMaxRaw == null || cpuinfoMaxRaw == null) return null;
        try {
            long scaling = Long.parseLong(scalingMaxRaw.trim());
            long nominal = Long.parseLong(cpuinfoMaxRaw.trim());
            if (scaling <= 0 || nominal <= 0) return null;
            return (float) scaling / (float) nominal;
        } catch (NumberFormatException error) {
            return null;
        }
    }
}
