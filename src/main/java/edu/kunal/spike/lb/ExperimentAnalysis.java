package edu.kunal.spike.lb;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Calculates Mean, Standard Deviation, Standard Error, and 95% Confidence Intervals
 * across independent seeds for all workloads, algorithms, and metrics.
 *
 * Implements:
 * - Table aggregations with 95% Student's t CI (Phase 2)
 * - Censored seed counting for recovery metrics (Table 6)
 * - Paired per-seed difference analysis: DERALBA_BF vs DERALBA_BF_NOREB (ER-3)
 *
 * Reads results/summary.csv and writes results/aggregate_stats.csv and results/paired_bf_vs_noreb.csv.
 */
public final class ExperimentAnalysis {

    private static final String[] METRICS = {
        "sim_makespan_s",
        "arur_paper",
        "imbalance_busy_max_over_mean",
        "throughput_jobs_per_s",
        "resp_mean_s",
        "resp_p95_s",
        "resp_max_s",
        "slowdown_assigned_mean",
        "slowdown_assigned_p95",
        "mean_imbalance_active",
        "recovery_s"
    };

    private static final Map<String, String> METRIC_LABELS = new LinkedHashMap<>();
    static {
        METRIC_LABELS.put("sim_makespan_s", "Makespan (s)");
        METRIC_LABELS.put("arur_paper", "ARUR Utilization");
        METRIC_LABELS.put("imbalance_busy_max_over_mean", "Busy Imbalance (Max/Mean)");
        METRIC_LABELS.put("throughput_jobs_per_s", "Throughput (jobs/s)");
        METRIC_LABELS.put("resp_mean_s", "Avg Response Time (s)");
        METRIC_LABELS.put("resp_p95_s", "95th-pct Response (s)");
        METRIC_LABELS.put("resp_max_s", "Max Response Time (s)");
        METRIC_LABELS.put("slowdown_assigned_mean", "Avg Assigned Slowdown");
        METRIC_LABELS.put("slowdown_assigned_p95", "95th-pct Assigned Slowdown");
        METRIC_LABELS.put("mean_imbalance_active", "Active Backlog Imbalance");
        METRIC_LABELS.put("recovery_s", "Recovery Time (s)");
    }

    public static final class Stats {
        public final int n;
        public final double mean;
        public final double std;
        public final double se;
        public final double ciLower;
        public final double ciUpper;

        public Stats(int n, double mean, double std, double se, double ciLower, double ciUpper) {
            this.n = n;
            this.mean = mean;
            this.std = std;
            this.se = se;
            this.ciLower = ciLower;
            this.ciUpper = ciUpper;
        }

        @Override
        public String toString() {
            if (Double.isNaN(mean)) {
                return "N/A";
            }
            return String.format(Locale.US, "%.2f ± %.2f [95%% CI: %.2f, %.2f] (sd=%.2f)",
                    mean, ciUpper - mean, ciLower, ciUpper, std);
        }
    }

    public static void main(String[] args) throws IOException {
        Path summaryFile = Paths.get(args.length > 0 ? args[0] : "results/summary.csv");
        Path outputFile = Paths.get(args.length > 1 ? args[1] : "results/aggregate_stats.csv");
        Path pairedFile = Paths.get(args.length > 2 ? args[2] : "results/paired_bf_vs_noreb.csv");

        if (!Files.exists(summaryFile)) {
            System.err.println("File not found: " + summaryFile.toAbsolutePath());
            return;
        }

        List<Map<String, String>> rows = readCsv(summaryFile);
        System.out.println("Loaded " + rows.size() + " experiment runs from " + summaryFile);

        // Group rows by: scenario -> algo_variant -> list of rows
        Map<String, Map<String, List<Map<String, String>>>> grouped = new LinkedHashMap<>();

        // Group rows for paired test: scenario -> round_len -> seed -> (algo -> row)
        Map<String, Map<String, Map<String, Map<String, Map<String, String>>>>> pairedMap = new LinkedHashMap<>();

        for (Map<String, String> row : rows) {
            String scenario = row.get("scenario");
            String algo = row.get("algo");
            String roundLen = row.get("round_len_s");
            String seed = row.get("seed");

            String key = (roundLen != null && !roundLen.isEmpty() && !roundLen.equals("NaN"))
                    ? algo + " (T=" + roundLen + "s)"
                    : algo;

            grouped.computeIfAbsent(scenario, k -> new LinkedHashMap<>())
                    .computeIfAbsent(key, k -> new ArrayList<>())
                    .add(row);

            if (roundLen != null && !roundLen.isEmpty() && !roundLen.equals("NaN")) {
                pairedMap.computeIfAbsent(scenario, k -> new LinkedHashMap<>())
                        .computeIfAbsent(roundLen, k -> new LinkedHashMap<>())
                        .computeIfAbsent(seed, k -> new LinkedHashMap<>())
                        .put(algo, row);
            }
        }

        StringBuilder csvOut = new StringBuilder();
        csvOut.append("scenario,algo_variant,metric,n_seeds,censored_seeds,mean,std_dev,std_error,ci95_lower,ci95_upper\n");

        for (Map.Entry<String, Map<String, List<Map<String, String>>>> scEntry : grouped.entrySet()) {
            String scenario = scEntry.getKey();
            System.out.println("\n" + "=".repeat(100));
            System.out.println("SCENARIO: " + scenario);
            System.out.println("=".repeat(100));

            for (String metric : METRICS) {
                String label = METRIC_LABELS.getOrDefault(metric, metric);
                System.out.printf("\n--- %s ---\n", label);
                System.out.printf("%-30s | %-12s | %-10s | %-10s | %-24s | %-10s\n",
                        "Algorithm", "Mean", "Std Dev", "Std Error", "95% Confidence Interval", "Censored");
                System.out.println("-".repeat(100));

                for (Map.Entry<String, List<Map<String, String>>> algEntry : scEntry.getValue().entrySet()) {
                    String algoKey = algEntry.getKey();
                    List<Double> values = new ArrayList<>();
                    int censoredCount = 0;

                    for (Map<String, String> row : algEntry.getValue()) {
                        String raw = row.get(metric);
                        if (raw != null && !raw.isEmpty() && !raw.equalsIgnoreCase("NaN")) {
                            try {
                                double v = Double.parseDouble(raw);
                                if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                                    values.add(v);
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                        if ("1".equals(row.get("recovery_censored"))) {
                            censoredCount++;
                        }
                    }

                    Stats s = computeStats(values);
                    String censoredDisplay = (metric.equals("recovery_s") && censoredCount > 0)
                            ? String.format("%d/%d (>=)", censoredCount, values.size())
                            : "-";

                    csvOut.append(String.format(Locale.US, "%s,%s,%s,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f\n",
                            scenario, algoKey, metric, s.n, censoredCount, s.mean, s.std, s.se, s.ciLower, s.ciUpper));

                    if (Double.isNaN(s.mean)) {
                        System.out.printf("%-30s | %-12s | %-10s | %-10s | %-24s | %-10s\n",
                                algoKey, "N/A", "N/A", "N/A", "N/A", censoredDisplay);
                    } else {
                        String prefix = (metric.equals("recovery_s") && censoredCount > 0) ? ">= " : "";
                        System.out.printf(Locale.US, "%-30s | %s%-10.3f | %-10.3f | %-10.3f | [%.3f, %.3f] | %-10s\n",
                                algoKey, prefix, s.mean, s.std, s.se, s.ciLower, s.ciUpper, censoredDisplay);
                    }
                }
            }
        }

        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        Files.writeString(outputFile, csvOut.toString());
        System.out.println("\nAggregate statistics saved to: " + outputFile.toAbsolutePath());

        // ER-3: Paired Per-Seed Difference Analysis (BF minus BF_NOREB)
        computePairedAnalysis(pairedMap, pairedFile);
    }

    /**
     * ER-3: Paired per-seed differences (DERALBA_BF minus DERALBA_BF_NOREB) with 95% Student's t CI.
     * Evaluates the isolated impact of the idle-VM rebalancer on response time, p95, makespan, and recovery.
     */
    private static void computePairedAnalysis(
            Map<String, Map<String, Map<String, Map<String, Map<String, String>>>>> pairedMap,
            Path pairedFile) throws IOException {

        System.out.println("\n" + "=".repeat(105));
        System.out.println("              ER-3 PAIRED PER-SEED DIFFERENCE ANALYSIS (DERALBA_BF minus BF_NOREB)      ");
        System.out.println("=".repeat(105));
        System.out.printf("%-18s | %-6s | %-22s | %-12s | %-10s | %-24s\n",
                "Scenario", "T (s)", "Metric", "Mean Diff", "Std Dev", "95% CI of Difference");
        System.out.println("-".repeat(105));

        StringBuilder pairedOut = new StringBuilder();
        pairedOut.append("scenario,round_len_s,metric,n_seeds,mean_diff,std_dev,std_error,ci95_lower,ci95_upper\n");

        String[] diffMetrics = {"resp_mean_s", "resp_p95_s", "sim_makespan_s", "recovery_s"};

        for (Map.Entry<String, Map<String, Map<String, Map<String, Map<String, String>>>>> scEntry : pairedMap.entrySet()) {
            String scenario = scEntry.getKey();
            for (Map.Entry<String, Map<String, Map<String, Map<String, String>>>> tEntry : scEntry.getValue().entrySet()) {
                String roundLen = tEntry.getKey();

                for (String m : diffMetrics) {
                    List<Double> diffs = new ArrayList<>();
                    for (Map<String, Map<String, String>> seedMap : tEntry.getValue().values()) {
                        Map<String, String> bfRow = seedMap.get("DERALBA_BF");
                        Map<String, String> noRebRow = seedMap.get("DERALBA_BF_NOREB");

                        if (bfRow != null && noRebRow != null) {
                            String v1 = bfRow.get(m);
                            String v2 = noRebRow.get(m);
                            if (v1 != null && v2 != null && !v1.equalsIgnoreCase("NaN") && !v2.equalsIgnoreCase("NaN")) {
                                try {
                                    double d1 = Double.parseDouble(v1);
                                    double d2 = Double.parseDouble(v2);
                                    diffs.add(d1 - d2);
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }

                    if (!diffs.isEmpty()) {
                        Stats s = computeStats(diffs);
                        String label = METRIC_LABELS.getOrDefault(m, m);
                        System.out.printf(Locale.US, "%-18s | %-6s | %-22s | %-12.4f | %-10.4f | [%.4f, %.4f]\n",
                                scenario, roundLen, label, s.mean, s.std, s.ciLower, s.ciUpper);

                        pairedOut.append(String.format(Locale.US, "%s,%s,%s,%d,%.4f,%.4f,%.4f,%.4f,%.4f\n",
                                scenario, roundLen, m, s.n, s.mean, s.std, s.se, s.ciLower, s.ciUpper));
                    }
                }
            }
        }

        if (pairedFile.getParent() != null) {
            Files.createDirectories(pairedFile.getParent());
        }
        Files.writeString(pairedFile, pairedOut.toString());
        System.out.println("\nPaired per-seed difference results written to: " + pairedFile.toAbsolutePath());
    }

    public static Stats computeStats(List<Double> vals) {
        int n = vals.size();
        if (n == 0) {
            return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double sum = 0.0;
        for (double v : vals) sum += v;
        double mean = sum / n;

        if (n == 1) {
            return new Stats(1, mean, 0.0, 0.0, mean, mean);
        }

        double sumSq = 0.0;
        for (double v : vals) {
            sumSq += (v - mean) * (v - mean);
        }
        double std = Math.sqrt(sumSq / (n - 1));
        double se = std / Math.sqrt(n);

        // Student's t critical value for alpha=0.05 (two-tailed 95% CI)
        double tCrit = getStudentTCriticalValue(n - 1);
        double margin = tCrit * se;

        return new Stats(n, mean, std, se, mean - margin, mean + margin);
    }

    private static double getStudentTCriticalValue(int df) {
        if (df <= 0) return 1.96;
        double[] tTable = {
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228, // 1-10
            2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086, // 11-20
            2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042  // 21-30
        };
        if (df <= 30) {
            return tTable[df - 1];
        } else if (df <= 40) {
            return 2.021;
        } else if (df <= 60) {
            return 2.000;
        } else if (df <= 120) {
            return 1.980;
        }
        return 1.960;
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<Map<String, String>> list = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String headerLine = br.readLine();
            if (headerLine == null) return list;
            String[] headers = parseCsvLine(headerLine);

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i], i < tokens.length ? tokens[i] : "");
                }
                list.add(row);
            }
        }
        return list;
    }

    private static String[] parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens.toArray(new String[0]);
    }
}
