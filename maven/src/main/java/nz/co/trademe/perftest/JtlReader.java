package nz.co.trademe.perftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reader for the CSV results log ({@code results.jtl}) written by the JMeter engine.
 * Java port of the data-loading logic in {@code scripts/validate-nfr.ps1}.
 */
public final class JtlReader {

    /** One API call from the results log. */
    public record SampleRecord(long timeStamp, int elapsed, String label, String responseCode,
                               String threadName, boolean success, String failureMessage,
                               int grpThreads, int allThreads) {
    }

    /** Aggregate statistics of one run, used by the NFR gates. */
    public record RunStats(int totalCalls, int successCount, int failureCount,
                           int minMs, int meanMs, int p50Ms, int p90Ms, int p95Ms, int p99Ms, int maxMs,
                           List<String> threadNames, int distinctThreads, int maxConcurrentThreads,
                           double rampSpreadSeconds, double observedCallsPerMinute) {
    }

    private JtlReader() {
    }

    /** Parses a JMeter CSV results log. The header row defines the column order. */
    public static List<SampleRecord> parse(Path resultsJtl) throws IOException {
        List<String> lines = Files.readAllLines(resultsJtl, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("Results file is empty: " + resultsJtl);
        }
        String[] header = splitCsvLine(lines.get(0));
        int[] index = new int[9];
        String[] required = {"timeStamp", "elapsed", "label", "responseCode",
                "threadName", "success", "failureMessage", "grpThreads", "allThreads"};
        for (int i = 0; i < required.length; i++) {
            index[i] = indexOf(header, required[i]);
            if (index[i] < 0) {
                throw new IOException("Results file is missing the '" + required[i]
                        + "' column - was the log written in XML format? File: " + resultsJtl);
            }
        }

        List<SampleRecord> samples = new ArrayList<>();
        for (int lineNo = 1; lineNo < lines.size(); lineNo++) {
            String line = lines.get(lineNo);
            if (line.isBlank()) {
                continue;
            }
            String[] fields = splitCsvLine(line);
            samples.add(new SampleRecord(
                    Long.parseLong(field(fields, index[0])),
                    (int) parseLong(field(fields, index[1])),
                    field(fields, index[2]),
                    field(fields, index[3]),
                    field(fields, index[4]),
                    Boolean.parseBoolean(field(fields, index[5])),
                    field(fields, index[6]),
                    (int) parseLong(field(fields, index[7])),
                    (int) parseLong(field(fields, index[8]))));
        }
        return samples;
    }

    /** Aggregates parsed samples into the run statistics consumed by {@link NfrValidator}. */
    public static RunStats summarise(List<SampleRecord> samples) {
        int total = samples.size();
        int successCount = 0;
        for (SampleRecord sample : samples) {
            if (sample.success()) {
                successCount++;
            }
        }

        int[] sortedElapsed = samples.stream().mapToInt(SampleRecord::elapsed).sorted().toArray();
        int minMs = sortedElapsed.length > 0 ? sortedElapsed[0] : 0;
        int maxMs = sortedElapsed.length > 0 ? sortedElapsed[sortedElapsed.length - 1] : 0;
        int meanMs = 0;
        if (sortedElapsed.length > 0) {
            long sum = 0;
            for (int value : sortedElapsed) {
                sum += value;
            }
            meanMs = (int) Math.round(sum / (double) sortedElapsed.length);
        }
        int p50 = percentileNearestRank(sortedElapsed, 50);
        int p90 = percentileNearestRank(sortedElapsed, 90);
        int p95 = percentileNearestRank(sortedElapsed, 95);
        int p99 = percentileNearestRank(sortedElapsed, 99);

        Set<String> threadNames = new LinkedHashSet<>();
        int maxConcurrent = 0;
        for (SampleRecord sample : samples) {
            threadNames.add(sample.threadName());
            maxConcurrent = Math.max(maxConcurrent, sample.allThreads());
        }

        // NFR-02 evidence: spread of the first sample of every VUser.
        List<Long> firstSamplePerThread = new ArrayList<>();
        for (String threadName : threadNames) {
            long first = Long.MAX_VALUE;
            for (SampleRecord sample : samples) {
                if (sample.threadName().equals(threadName)) {
                    first = Math.min(first, sample.timeStamp());
                }
            }
            firstSamplePerThread.add(first);
        }
        firstSamplePerThread.sort(Comparator.naturalOrder());
        double rampSpreadSeconds = 0.0;
        if (firstSamplePerThread.size() >= 2) {
            rampSpreadSeconds = round1((firstSamplePerThread.get(firstSamplePerThread.size() - 1)
                    - firstSamplePerThread.get(0)) / 1000.0);
        }

        // NFR-03 evidence: observed throughput across the active window.
        double observedCallsPerMinute = 0.0;
        if (total >= 2) {
            long firstStart = Long.MAX_VALUE;
            long lastEnd = 0;
            for (SampleRecord sample : samples) {
                firstStart = Math.min(firstStart, sample.timeStamp());
                lastEnd = Math.max(lastEnd, sample.timeStamp() + sample.elapsed());
            }
            long activeWindowMs = Math.max(lastEnd - firstStart, 1000);
            observedCallsPerMinute = round1(total * 60000.0 / activeWindowMs);
        }

        return new RunStats(total, successCount, total - successCount,
                minMs, meanMs, p50, p90, p95, p99, maxMs,
                List.copyOf(threadNames), threadNames.size(), maxConcurrent,
                rampSpreadSeconds, observedCallsPerMinute);
    }

    /**
     * Nearest-rank percentile - identical to the {@code Get-Percentile} helper in
     * {@code validate-nfr.ps1}: {@code rank = ceil(percentile / 100 * n)}, clamped to [1, n].
     */
    static int percentileNearestRank(int[] sortedValues, double percentile) {
        if (sortedValues.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * sortedValues.length);
        rank = Math.max(1, Math.min(sortedValues.length, rank));
        return sortedValues[rank - 1];
    }

    /** Counts data rows (header excluded) of an RFC 4180 CSV file, honouring multi-line quoted fields. */
    public static int countCsvDataRows(Path csv) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        boolean inQuotes = false;
        boolean headerSeen = false;
        int records = 0;
        for (String line : lines) {
            if (!inQuotes) {
                if (!headerSeen) {
                    headerSeen = true; // first record is the header
                } else if (!line.isBlank()) {
                    records++; // a new data record starts on this line
                }
            }
            inQuotes = togglesQuote(line, inQuotes);
        }
        return records;
    }

    private static boolean togglesQuote(String line, boolean inQuotesBefore) {
        boolean inQuotes = inQuotesBefore;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    i++; // escaped quote
                } else {
                    inQuotes = !inQuotes;
                }
            }
        }
        return inQuotes;
    }

    /**
     * Splits one RFC 4180 CSV line, honouring double-quoted fields with embedded
     * commas and escaped (doubled) quotes.
     */
    static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static String field(String[] fields, int index) {
        return index < fields.length ? fields[index] : "";
    }

    private static long parseLong(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? 0 : Long.parseLong(trimmed);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static int indexOf(String[] header, String column) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].equals(column)) {
                return i;
            }
        }
        return -1;
    }
}
