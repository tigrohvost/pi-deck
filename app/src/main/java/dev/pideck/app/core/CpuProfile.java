package dev.pideck.app.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Picks a stable decode/batch split for heterogeneous Arm phones.
 *
 * <p>Decode is latency-bound and uses at most the five fastest cores. Prompt ingestion is
 * throughput-bound and may use every online core. The resulting affinity strings are passed as
 * individual llama.cpp argv elements; no shell parsing is involved.
 */
public final class CpuProfile {
    public static final int MAX_DECODE_THREADS = 5;
    public static final int MAX_BATCH_THREADS = 8;

    public final int decodeThreads;
    public final int batchThreads;
    public final String decodeCpuSet;
    public final String batchCpuSet;

    private CpuProfile(
            int decodeThreads,
            int batchThreads,
            String decodeCpuSet,
            String batchCpuSet
    ) {
        this.decodeThreads = decodeThreads;
        this.batchThreads = batchThreads;
        this.decodeCpuSet = decodeCpuSet;
        this.batchCpuSet = batchCpuSet;
    }

    public static CpuProfile detect() {
        int count = detectCpuCount();
        long[] frequencies = new long[count];
        for (int cpu = 0; cpu < count; cpu++) {
            frequencies[cpu] = readFrequency(cpu);
        }
        return fromMaxFrequencies(frequencies);
    }

    static CpuProfile fromMaxFrequencies(long[] maximumFrequencies) {
        if (maximumFrequencies == null || maximumFrequencies.length == 0) {
            throw new IllegalArgumentException("At least one CPU is required");
        }
        int coreCount = maximumFrequencies.length;
        int decodeCount = Math.min(MAX_DECODE_THREADS, coreCount);
        Integer[] ranked = new Integer[coreCount];
        for (int cpu = 0; cpu < coreCount; cpu++) ranked[cpu] = cpu;
        Arrays.sort(ranked, Comparator
                .comparingLong((Integer cpu) -> maximumFrequencies[cpu])
                .reversed()
                .thenComparing(Comparator.reverseOrder()));

        int[] decodeCpus = new int[decodeCount];
        for (int index = 0; index < decodeCount; index++) decodeCpus[index] = ranked[index];
        Arrays.sort(decodeCpus);

        int batchCount = Math.min(MAX_BATCH_THREADS, coreCount);
        int[] batchCpus = new int[batchCount];
        for (int cpu = 0; cpu < batchCount; cpu++) batchCpus[cpu] = cpu;
        return new CpuProfile(
                decodeCount,
                batchCount,
                cpuList(decodeCpus),
                cpuList(batchCpus)
        );
    }

    private static int detectCpuCount() {
        int count = 0;
        while (Files.isDirectory(Paths.get("/sys/devices/system/cpu/cpu" + count))) count++;
        return count > 0 ? count : Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private static long readFrequency(int cpu) {
        for (String name : List.of("cpuinfo_max_freq", "scaling_max_freq")) {
            Path path = Paths.get(
                    "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/" + name
            );
            try {
                return Long.parseLong(
                        new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim()
                );
            } catch (IOException | NumberFormatException ignored) {
            }
        }
        // A homogeneous/frequency-hidden device remains correct: higher IDs are preferred only
        // as a deterministic tie-break, and the thread count stays bounded.
        return 0L;
    }

    private static String cpuList(int[] cpus) {
        ArrayList<String> ranges = new ArrayList<>();
        int start = cpus[0];
        int end = start;
        for (int index = 1; index < cpus.length; index++) {
            if (cpus[index] == end + 1) {
                end = cpus[index];
                continue;
            }
            ranges.add(range(start, end));
            start = cpus[index];
            end = start;
        }
        ranges.add(range(start, end));
        return String.join(",", ranges);
    }

    private static String range(int start, int end) {
        return start == end ? Integer.toString(start) : start + "-" + end;
    }

    @Override
    public String toString() {
        return "decode=" + decodeThreads + "@" + decodeCpuSet
                + ", batch=" + batchThreads + "@" + batchCpuSet;
    }
}
