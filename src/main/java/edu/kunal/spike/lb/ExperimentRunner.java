package edu.kunal.spike.lb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes comparative performance evaluations across cloud workload scenarios and scheduling algorithms.
 *
 * For every scenario and seed it builds ONE workload and runs every algorithm on that same workload:
 *   RR, EFT, DERALBA_AUTHOR_ASIS (replica of the reference implementation, always T = 1 s),
 *   and for each round length in ROUND_LENS:
 *   MAX_MIN (Max-Min in rounds, starting from the VMs' current queue ends),
 *   DERALBA_BF (BIG_FIRST + rebalancing), DERALBA_LIT (literal + rebalancing),
 *   DERALBA_BF_NOREB (BIG_FIRST, no rebalancing), DERALBA_LIT_BACKLOG (literal + backlog-aware share).
 * Each run becomes one row in results/summary.csv. For the first seed it also writes the backlog
 * time series to results/series/ (for plots).
 *
 * Arguments (all optional):  [poolFile] [seeds] [outDir]
 *   poolFile  default GoCJ_100.txt (job sizes, relative to the working directory)
 *   seeds     default 30 (seeds 1..N). Use 2 for a quick check.
 *   outDir    default results
 */
public final class ExperimentRunner {

    // same cluster as SpikeLb: {MIPS, how many VMs}
    private static final int[][] VM_GROUPS = {
        {100, 7}, {500, 7}, {550, 6}, {1000, 6}, {1250, 6}, {1500, 6}, {1750, 6}, {2000, 6}
    };

    /** Round lengths tried for the round-based algorithms, in seconds. */
    private static final double[] ROUND_LENS = {1.0, 5.0, 30.0};

    private static final int JOBS = 1000;
    private static final double BASE_RHO = 0.5;
    private static final double SPIKE_START_S = 1000.0;
    private static final double SPIKE_LEN_S = 10.0;
    private static final double[] SPIKE_MULTIPLIERS = {4, 20, 60, 150};

    private ExperimentRunner() {
    }

    private static double[] buildVmMips() {
        List<Double> list = new ArrayList<>();
        for (int[] g : VM_GROUPS) {
            for (int k = 0; k < g[1]; k++) {
                list.add((double) g[0]);
            }
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private static List<WorkloadGen.Config> scenarios(double totalMips) {
        List<WorkloadGen.Config> list = new ArrayList<>();
        list.add(WorkloadGen.Config.steady("steady_rho0.5", JOBS, totalMips, BASE_RHO));
        list.add(WorkloadGen.Config.steady("steady_rho0.8", JOBS, totalMips, 0.8));
        for (double x : SPIKE_MULTIPLIERS) {
            list.add(WorkloadGen.Config.spike("spike_x" + (int) x, JOBS, totalMips, BASE_RHO,
                    x, SPIKE_START_S, SPIKE_LEN_S));
        }
        return list;
    }

    /** {mean, max} number of jobs per non-empty round of length T. */
    private static double[] roundSizeStats(double[] arrival, double roundLen) {
        int rounds = 0;
        int cur = 0;
        int maxSize = 0;
        long lastRound = Long.MIN_VALUE;
        for (double a : arrival) {
            long r = (long) Math.floor(a / roundLen);
            if (r != lastRound) {
                if (cur > 0) {
                    maxSize = Math.max(maxSize, cur);
                }
                rounds++;
                cur = 0;
                lastRound = r;
            }
            cur++;
        }
        maxSize = Math.max(maxSize, cur);
        return new double[] {(double) arrival.length / rounds, maxSize};
    }

    private static final class Counters {
        int runs = 0;
        int modelMismatches = 0;
    }

    public static void main(String[] args) throws Exception {
        Path poolFile = Paths.get(args.length > 0 ? args[0] : "GoCJ_100.txt");
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        Path outDir = Paths.get(args.length > 2 ? args[2] : "results");

        double[] pool = WorkloadGen.loadPool(poolFile);
        double[] vmMips = buildVmMips();
        double totalMips = 0;
        for (double v : vmMips) {
            totalMips += v;
        }
        double poolMean = 0;
        double poolMax = 0;
        for (double v : pool) {
            poolMean += v;
            poolMax = Math.max(poolMax, v);
        }
        poolMean /= pool.length;
        double baseRate = BASE_RHO * totalMips / poolMean;

        System.out.println("Pool: " + pool.length + " job sizes from " + poolFile + ", mean " + Math.round(poolMean)
                + " MI, max " + Math.round(poolMax) + " MI");
        System.out.println("Cluster: " + vmMips.length + " VMs, " + Math.round(totalMips) + " MIPS total");
        System.out.println("Base arrival rate at rho=" + BASE_RHO + ": " + String.format("%.4f", baseRate) + " jobs/s");
        System.out.println("Lower bound on any makespan (largest job on fastest VM): "
                + String.format("%.1f", poolMax / 2000.0) + " s");
        for (double x : SPIKE_MULTIPLIERS) {
            for (double t : ROUND_LENS) {
                System.out.println(String.format("  spike x%-4d  round %.1f s  ->  about %.1f jobs per round in the spike",
                        (int) x, t, baseRate * x * t));
            }
        }
        System.out.println("Seeds: " + seeds + ", output: " + outDir.toAbsolutePath());

        // ER-5: save run metadata right after outDir is known
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("run_info.txt"),
                "java=" + System.getProperty("java.version")
                + "\nseeds=" + seeds + "\njobs=" + JOBS + "\nrho=" + BASE_RHO
                + "\nround_lens=" + java.util.Arrays.toString(ROUND_LENS)
                + "\nspike_multipliers=" + java.util.Arrays.toString(SPIKE_MULTIPLIERS)
                + "\nstart=" + java.time.Instant.now() + "\n");

        Path summary = outDir.resolve("summary.csv");
        // Safe auto-backup so results are preserved and never lost, while allowing smooth execution in NetBeans
        if (Files.exists(summary)) {
            Path backup = outDir.resolve("summary_backup_" + System.currentTimeMillis() + ".csv");
            Files.copy(summary, backup, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Note: Existing summary preserved at " + backup.getFileName());
            Files.delete(summary);
        }

        Counters counters = new Counters();
        long t0 = System.currentTimeMillis();
        for (WorkloadGen.Config cfg : scenarios(totalMips)) {
            for (int s = 1; s <= seeds; s++) {
                WorkloadGen.Workload wl = WorkloadGen.generate(cfg, pool, s);
                boolean series = (s == 1);

                runBaseline("RR", Baselines.roundRobin(wl.arrival, vmMips.length), wl, vmMips,
                        summary, outDir, series, counters);
                runBaseline("EFT", Baselines.eft(wl.arrival, wl.mi, vmMips), wl, vmMips,
                        summary, outDir, series, counters);

                runAuthorAsIs(wl, vmMips, summary, outDir, series, counters);

                for (double roundLen : ROUND_LENS) {
                    runBaseline("MAX_MIN_T" + roundLen,
                            Baselines.maxMinRounds(wl.arrival, wl.mi, vmMips, roundLen),
                            wl, vmMips, summary, outDir, series, counters);

                    runDeRalba("DERALBA_BF", DeRalba.Options.BIGFIRST, true, roundLen,
                            wl, vmMips, summary, outDir, series, counters);
                    runDeRalba("DERALBA_LIT", DeRalba.Options.LITERAL, true, roundLen,
                            wl, vmMips, summary, outDir, series, counters);
                    runDeRalba("DERALBA_BF_NOREB", DeRalba.Options.BIGFIRST, false, roundLen,
                            wl, vmMips, summary, outDir, series, counters);
                    runDeRalba("DERALBA_LIT_BACKLOG", DeRalba.Options.LIT_BACKLOG, true, roundLen,
                            wl, vmMips, summary, outDir, series, counters);
                }
            }
            System.out.println(String.format("done %-16s (%d seeds, %d runs so far, %.0f s elapsed)",
                    cfg.name, seeds, counters.runs, (System.currentTimeMillis() - t0) / 1000.0));
        }
        System.out.println();
        System.out.println(counters.runs + " runs written to " + summary);
        System.out.println("Model-vs-queue makespan mismatches: " + counters.modelMismatches
                + (counters.modelMismatches == 0 ? "  (good)" : "  (CHECK THESE, see warnings above)"));
    }

    private static void runBaseline(String algo, Baselines.Assignment a, WorkloadGen.Workload wl, double[] vmMips,
                                    Path summary, Path outDir, boolean series, Counters counters) throws IOException {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("algo", algo);
        extra.put("bigfirst", null);
        extra.put("rebalance", null);
        extra.put("round_len_s", null);
        extra.put("rounds", null);
        extra.put("mean_jobs_per_round", null);
        extra.put("max_jobs_per_round", null);
        extra.put("share_jobs", null);
        extra.put("eft_jobs", null);
        extra.put("share_fraction", null);
        extra.put("migrations", null);
        finish(a.vm, a.dispatch, wl, vmMips, extra, algo, summary, outDir, series, counters);
    }

    private static void runDeRalba(String algo, DeRalba.Options opts, boolean rebalance, double roundLen,
                                   WorkloadGen.Workload wl, double[] vmMips, Path summary, Path outDir,
                                   boolean series, Counters counters) throws IOException {
        DeRalbaDynamic.Result res = DeRalbaDynamic.run(wl.arrival, wl.mi, vmMips, roundLen, rebalance, opts);
        double[] rs = roundSizeStats(wl.arrival, roundLen);
        int phaseJobs = res.shareJobs + res.eftJobs;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("algo", algo);
        extra.put("bigfirst", opts.bigFirst ? 1 : 0);
        extra.put("rebalance", rebalance ? 1 : 0);
        extra.put("round_len_s", roundLen);
        extra.put("rounds", res.rounds);
        extra.put("mean_jobs_per_round", rs[0]);
        extra.put("max_jobs_per_round", (int) rs[1]);
        extra.put("share_jobs", res.shareJobs);
        extra.put("eft_jobs", res.eftJobs);
        extra.put("share_fraction", phaseJobs > 0 ? (double) res.shareJobs / phaseJobs : Double.NaN);
        extra.put("migrations", res.migrations);

        String name = algo + "__T" + roundLen;
        double model = finish(res.finalVm, res.dispatchAt, wl, vmMips, extra, name, summary, outDir, series, counters);
        if (Math.abs(model - res.predictedMakespan) > 1e-6 * Math.max(1.0, model)) {
            counters.modelMismatches++;
            System.out.println("WARNING " + name + " " + wl.config.name + " seed " + wl.seed
                    + ": model makespan " + res.predictedMakespan + " but queue makespan " + model);
        }
    }

    /** Replica of the reference implementation (DRalba.java), decisions on 1-second ticks. */
    private static void runAuthorAsIs(WorkloadGen.Workload wl, double[] vmMips, Path summary, Path outDir,
                                      boolean series, Counters counters) throws IOException {
        DeRalbaDynamic.Result res = DeRalbaAuthor.run(wl.arrival, wl.mi, vmMips);
        double[] rs = roundSizeStats(wl.arrival, DeRalbaAuthor.TICK);
        int phaseJobs = res.shareJobs + res.eftJobs;

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("algo", "DERALBA_AUTHOR_ASIS");
        extra.put("bigfirst", 0);
        extra.put("rebalance", 1);
        extra.put("round_len_s", DeRalbaAuthor.TICK);
        extra.put("rounds", res.rounds);
        extra.put("mean_jobs_per_round", rs[0]);
        extra.put("max_jobs_per_round", (int) rs[1]);
        extra.put("share_jobs", res.shareJobs);
        extra.put("eft_jobs", res.eftJobs);
        extra.put("share_fraction", phaseJobs > 0 ? (double) res.shareJobs / phaseJobs : Double.NaN);
        extra.put("migrations", res.migrations);

        String name = "DERALBA_AUTHOR_ASIS__T" + DeRalbaAuthor.TICK;
        double model = finish(res.finalVm, res.dispatchAt, wl, vmMips, extra, name, summary, outDir, series, counters);
        if (Math.abs(model - res.predictedMakespan) > 1e-6 * Math.max(1.0, model)) {
            counters.modelMismatches++;
            System.out.println("WARNING " + name + " " + wl.config.name + " seed " + wl.seed
                    + ": model makespan " + res.predictedMakespan + " but queue makespan " + model);
        }
    }

    /** Executes the assignment, computes metrics, writes the CSV row (and series). Returns the queue makespan. */
    private static double finish(int[] vm, double[] dispatch, WorkloadGen.Workload wl, double[] vmMips,
                                 Map<String, Object> extra, String runName, Path summary, Path outDir,
                                 boolean series, Counters counters) throws IOException {
        List<SpikeMetrics.JobRecord> recs = QueueExec.run(wl.arrival, wl.mi, vm, dispatch, vmMips);
        SpikeMetrics.Result r = SpikeMetrics.compute(recs, vmMips, wl.spikes, new SpikeMetrics.Params());

        LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        row.putAll(wl.describe());
        row.putAll(extra);
        row.putAll(r.row);
        CsvLog.appendRow(summary, row);
        counters.runs++;

        if (series) {
            LinkedHashMap<String, double[]> cols = new LinkedHashMap<>();
            cols.put("time_s", r.time);
            cols.put("pending_jobs", r.pendingJobs);
            cols.put("cluster_backlog_s", r.clusterBacklogSec);
            cols.put("max_vm_backlog_s", r.maxVmBacklogSec);
            cols.put("mean_vm_backlog_s", r.meanVmBacklogSec);
            cols.put("imbalance", r.imbalance);
            CsvLog.writeSeries(outDir.resolve("series").resolve(wl.config.name + "__" + runName + "__seed"
                    + wl.seed + ".csv"), cols);
        }
        return (Double) r.row.get("sim_makespan_s");
    }
}
