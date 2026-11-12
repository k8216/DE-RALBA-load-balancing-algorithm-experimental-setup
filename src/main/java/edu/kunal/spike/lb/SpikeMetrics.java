package edu.kunal.spike.lb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Burst-focused metrics computed from per-job results, plus a backlog time series.
 * Works on plain numbers (no CloudSim types), so it can be unit-tested on its own.
 *
 * Definitions
 *   response  = finish - arrival            (includes any wait at the scheduler)
 *   wait      = start  - arrival
 *   slowdown  = response / (mi / fastestMips)   (1.0 = ran at once on the fastest VM)
 *   slowdown_assigned = response / (mi / vmMips[assigned]) (slowdown relative to assigned VM)
 *
 * Backlog series, sampled every dt seconds:
 *   clusterBacklogSec  = remaining MI of all arrived, unfinished jobs / total MIPS
 *                        (seconds the cluster needs to drain if perfectly balanced;
 *                        includes jobs still waiting for the scheduler's next round)
 *   maxVmBacklogSec    = largest per-VM backlog in seconds (only jobs already dispatched to a VM)
 *   meanVmBacklogSec   = mean of the per-VM backlogs over all VMs, idle ones included
 *   imbalance          = (max - mean) / mean, 0 when the mean is 0
 *
 * Assumptions to state in the paper
 *   - Remaining MI is an estimate, not CloudSim's internal counter: a job counts as fully remaining
 *     until it starts, then falls linearly to zero at its finish time. This is exact for one job per
 *     PE at constant MIPS (space-shared, 1-PE VMs) and approximate otherwise.
 *   - clusterBacklogSec counts jobs from their arrival, including jobs still waiting at the scheduler.
 *     maxVmBacklogSec and imbalance count a job only from its dispatch time, so the two are not the
 *     same quantity. With a round-based scheduler the gap between them is the scheduler's batching delay.
 *
 * Recovery time (settling time after the first spike):
 *   baseline  = mean clusterBacklogSec over the second half of the pre-spike period (std = its std deviation)
 *   threshold = baseline + max(recoveryTol * baseline, recoverySigma * std) + recoveryFloorSec
 *               (the allowance for normal job-size noise matters: with indivisible jobs the backlog wobbles
 *               even without a spike, so a bare "baseline + 25%" would be crossed at random)
 *   recovery  = time from the spike's end to the first moment the series stays at or below the threshold for
 *               recoveryHoldSec in a row, observed up to the next spike or the last arrival, whichever is first.
 *   recovery_censored = 1 if that never happened inside the window (recovery_s is then a lower bound).
 *
 * The window stops at the last arrival on purpose. After arrivals stop, every scheduler's backlog drains
 * to zero, so extending the window to the end of the simulation would report "recovered" for every run
 * and hide the differences. Use post_spike_observed_s to see how long the window was, and
 * backlog_at_last_arrival_s to see how much work was still queued when it closed. Compare algorithms by
 * recovery_s only when the censored flag matches; otherwise compare backlog_at_last_arrival_s.
 */
public final class SpikeMetrics {

    private SpikeMetrics() {
    }

    public static final class JobRecord {
        public final long id;
        public final int vm;
        public final double mi;
        public final double arrival;
        public final double dispatch;
        public final double start;
        public final double finish;

        public JobRecord(long id, int vm, double mi, double arrival, double dispatch, double start, double finish) {
            this.id = id;
            this.vm = vm;
            this.mi = mi;
            this.arrival = arrival;
            this.dispatch = dispatch;
            this.start = start;
            this.finish = finish;
        }
    }

    public static final class Params {
        public double dt = 1.0;
        public double recoveryTol = 0.25;
        public double recoverySigma = 2.0;
        public double recoveryFloorSec = 1.0;
        /** Backlog must stay under the threshold this long to count as recovered. */
        public double recoveryHoldSec = 60.0;
        /** Upper bound on the number of series samples; dt is enlarged if needed. */
        public int maxSamples = 200_000;
    }

    public static final class Result {
        /** One row of summary columns, in a fixed order. */
        public final LinkedHashMap<String, Object> row = new LinkedHashMap<>();
        public double[] time;
        public double[] pendingJobs;
        public double[] clusterBacklogSec;
        public double[] maxVmBacklogSec;
        public double[] meanVmBacklogSec;
        public double[] imbalance;
        public double dt;
    }

    public static Result compute(List<JobRecord> jobs, double[] vmMips, List<WorkloadGen.Window> spikes, Params p) {
        int n = jobs.size();
        int m = vmMips.length;
        if (n == 0) {
            throw new IllegalArgumentException("No job records to analyse.");
        }
        if (m == 0) {
            throw new IllegalArgumentException("No VMs given.");
        }
        double totalMips = 0.0;
        double fastest = 0.0;
        for (double v : vmMips) {
            if (!Double.isFinite(v) || v <= 0) {
                throw new IllegalArgumentException("VM MIPS must be finite and > 0, got " + v);
            }
            totalMips += v;
            fastest = Math.max(fastest, v);
        }
        for (JobRecord r : jobs) {
            if (r.vm < 0 || r.vm >= m) {
                throw new IllegalArgumentException("Job " + r.id + " has VM index " + r.vm + ", expected 0.." + (m - 1));
            }
            if (!Double.isFinite(r.mi) || r.mi <= 0 || !Double.isFinite(r.arrival) || !Double.isFinite(r.dispatch)
                    || !Double.isFinite(r.start) || !Double.isFinite(r.finish)) {
                throw new IllegalArgumentException("Job " + r.id + " has a non-finite or non-positive value.");
            }
            if (r.finish < r.start || r.start < r.arrival - 1e-6) {
                throw new IllegalArgumentException("Job " + r.id + " has inconsistent times: arrival=" + r.arrival
                        + " start=" + r.start + " finish=" + r.finish);
            }
            // dispatch is when the job reached its VM: never before it arrived, never after it started
            if (r.dispatch < r.arrival - 1e-6 || r.dispatch > r.start + 1e-6) {
                throw new IllegalArgumentException("Job " + r.id + " has dispatch outside [arrival, start]: arrival="
                        + r.arrival + " dispatch=" + r.dispatch + " start=" + r.start);
            }
        }

        double[] resp = new double[n];
        double[] wait = new double[n];
        double[] slow = new double[n];
        double[] slowAssigned = new double[n];
        double[] busy = new double[m];
        double maxFinish = 0.0;
        double lastArrival = 0.0;
        for (int i = 0; i < n; i++) {
            JobRecord r = jobs.get(i);
            resp[i] = r.finish - r.arrival;
            wait[i] = r.start - r.arrival;
            slow[i] = resp[i] / (r.mi / fastest);
            slowAssigned[i] = resp[i] / (r.mi / vmMips[r.vm]);
            busy[r.vm] += r.mi / vmMips[r.vm];
            maxFinish = Math.max(maxFinish, r.finish);
            lastArrival = Math.max(lastArrival, r.arrival);
        }
        double maxBusy = 0.0;
        double sumBusy = 0.0;
        for (double b : busy) {
            maxBusy = Math.max(maxBusy, b);
            sumBusy += b;
        }

        Result res = new Result();
        LinkedHashMap<String, Object> row = res.row;
        row.put("jobs", n);
        row.put("last_arrival_s", lastArrival);
        row.put("sim_makespan_s", maxFinish);
        row.put("drain_after_last_arrival_s", maxFinish - lastArrival);
        row.put("makespan_paper_s", maxBusy);
        row.put("arur_paper", maxBusy > 0 ? (sumBusy / m) / maxBusy : Double.NaN);

        // SM-1: Paper's Eq. 32 imbalance (max busy / mean busy)
        double meanBusy = sumBusy / m;
        row.put("imbalance_busy_max_over_mean", meanBusy > 0 ? maxBusy / meanBusy : Double.NaN);

        row.put("throughput_jobs_per_s", maxFinish > 0 ? n / maxFinish : Double.NaN);
        row.put("resp_mean_s", mean(resp));
        row.put("resp_p50_s", percentile(resp, 50));
        row.put("resp_p95_s", percentile(resp, 95));
        row.put("resp_p99_s", percentile(resp, 99));
        row.put("resp_max_s", max(resp));
        row.put("wait_mean_s", mean(wait));
        row.put("wait_p95_s", percentile(wait, 95));
        row.put("slowdown_mean", mean(slow));
        row.put("slowdown_p95", percentile(slow, 95));
        row.put("slowdown_p99", percentile(slow, 99));

        // SM-2: Slowdown relative to the assigned VM (paper Eq. 30)
        row.put("slowdown_assigned_mean", mean(slowAssigned));
        row.put("slowdown_assigned_p95", percentile(slowAssigned, 95));

        for (String phase : new String[] {WorkloadGen.PRE, WorkloadGen.SPIKE, WorkloadGen.AFTER}) {
            List<Double> pr = new ArrayList<>();
            List<Double> ps = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (WorkloadGen.phaseOf(spikes, jobs.get(i).arrival).equals(phase)) {
                    pr.add(resp[i]);
                    ps.add(slow[i]);
                }
            }
            double[] a = toArray(pr);
            double[] b = toArray(ps);
            row.put("n_" + phase, a.length);
            row.put("resp_mean_" + phase + "_s", mean(a));
            row.put("resp_p95_" + phase + "_s", percentile(a, 95));
            row.put("slowdown_mean_" + phase, mean(b));
        }

        // ---- backlog time series ----
        double dt = p.dt;
        int k = (int) Math.floor(maxFinish / dt) + 1;
        if (k > p.maxSamples) {
            k = p.maxSamples;
            dt = maxFinish / (k - 1);
        }
        double[] pendingMi = new double[k];
        double[] pendingCount = new double[k];
        double[][] vmMi = new double[m][k];
        for (JobRecord r : jobs) {
            int from = Math.max(0, (int) Math.ceil(r.arrival / dt - 1e-9));
            for (int s = from; s < k && s * dt < r.finish; s++) {
                pendingMi[s] += remaining(r, s * dt);
                pendingCount[s] += 1.0;
            }
            int vFrom = Math.max(0, (int) Math.ceil(r.dispatch / dt - 1e-9));
            for (int s = vFrom; s < k && s * dt < r.finish; s++) {
                vmMi[r.vm][s] += remaining(r, s * dt);
            }
        }

        res.dt = dt;
        res.time = new double[k];
        res.pendingJobs = pendingCount;
        res.clusterBacklogSec = new double[k];
        res.maxVmBacklogSec = new double[k];
        res.meanVmBacklogSec = new double[k];
        res.imbalance = new double[k];
        for (int s = 0; s < k; s++) {
            res.time[s] = s * dt;
            res.clusterBacklogSec[s] = pendingMi[s] / totalMips;
            double mx = 0.0;
            double sum = 0.0;
            for (int j = 0; j < m; j++) {
                double sec = vmMi[j][s] / vmMips[j];
                mx = Math.max(mx, sec);
                sum += sec;
            }
            double mn = sum / m;
            res.maxVmBacklogSec[s] = mx;
            res.meanVmBacklogSec[s] = mn;
            res.imbalance[s] = mn > 0 ? (mx - mn) / mn : 0.0;
        }

        int peakIdx = argMax(res.clusterBacklogSec);
        row.put("peak_cluster_backlog_s", res.clusterBacklogSec[peakIdx]);
        row.put("peak_cluster_backlog_at_s", res.time[peakIdx]);
        row.put("peak_max_vm_backlog_s", max(res.maxVmBacklogSec));
        row.put("mean_cluster_backlog_active_s", meanUpTo(res.clusterBacklogSec, res.time, lastArrival));
        row.put("mean_imbalance_active", meanUpTo(res.imbalance, res.time, lastArrival));

        double baseline = Double.NaN;
        double baselineStd = Double.NaN;
        double threshold = Double.NaN;
        double recovery = Double.NaN;
        double observed = Double.NaN;
        int censored = 0;
        if (!spikes.isEmpty()) {
            WorkloadGen.Window first = spikes.get(0);
            baseline = meanBetween(res.clusterBacklogSec, res.time, first.start / 2.0, first.start);
            baselineStd = stdBetween(res.clusterBacklogSec, res.time, first.start / 2.0, first.start);
            double horizon = spikes.size() > 1 ? Math.min(spikes.get(1).start, lastArrival) : lastArrival;
            observed = Math.max(0.0, horizon - first.end);
            if (Double.isNaN(baseline) || first.end >= horizon) {
                censored = 1;
            } else {
                double noise = Double.isNaN(baselineStd) ? 0.0 : p.recoverySigma * baselineStd;
                threshold = baseline + Math.max(p.recoveryTol * baseline, noise) + p.recoveryFloorSec;
                int hold = Math.max(1, (int) Math.ceil(p.recoveryHoldSec / dt));
                int found = -1;
                int streakStart = -1;
                int streak = 0;
                for (int s = 0; s < k; s++) {
                    double t = res.time[s];
                    if (t < first.end) {
                        continue;
                    }
                    if (t > horizon) {
                        break;
                    }
                    if (res.clusterBacklogSec[s] <= threshold) {
                        if (streak == 0) {
                            streakStart = s;
                        }
                        streak++;
                        if (streak >= hold) {
                            found = streakStart;
                            break;
                        }
                    } else {
                        streak = 0;
                    }
                }
                if (found >= 0) {
                    recovery = Math.max(0.0, res.time[found] - first.end);
                } else {
                    censored = 1;
                    recovery = horizon - first.end;
                }
            }
        }
        double atLastArrival = Double.NaN;
        for (int s = 0; s < k; s++) {
            if (res.time[s] <= lastArrival) {
                atLastArrival = res.clusterBacklogSec[s];
            }
        }
        row.put("baseline_backlog_s", baseline);
        row.put("baseline_backlog_std_s", baselineStd);
        row.put("recovery_threshold_s", threshold);
        row.put("recovery_s", recovery);
        row.put("recovery_censored", censored);
        row.put("post_spike_observed_s", observed);
        row.put("backlog_at_last_arrival_s", atLastArrival);

        // SM-3: Write recovery parameters into every row
        row.put("recovery_tol", p.recoveryTol);
        row.put("recovery_sigma", p.recoverySigma);
        row.put("recovery_floor_s", p.recoveryFloorSec);
        row.put("recovery_hold_s", p.recoveryHoldSec);
        row.put("series_dt_s", dt);

        return res;
    }

    /** MI still to be executed for the job at time t (full length until it starts, then linear). */
    static double remaining(JobRecord r, double t) {
        if (t < r.start) {
            return r.mi;
        }
        double span = r.finish - r.start;
        if (span <= 0) {
            return 0.0;
        }
        return r.mi * Math.max(0.0, (r.finish - t) / span);
    }

    /** Linear-interpolation percentile (the usual "type 7"); NaN for an empty array. */
    public static double percentile(double[] values, double pct) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] a = values.clone();
        Arrays.sort(a);
        double pos = (pct / 100.0) * (a.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        return a[lo] + (pos - lo) * (a[hi] - a[lo]);
    }

    static double mean(double[] a) {
        if (a.length == 0) {
            return Double.NaN;
        }
        double s = 0.0;
        for (double v : a) {
            s += v;
        }
        return s / a.length;
    }

    static double max(double[] a) {
        if (a.length == 0) {
            return Double.NaN;
        }
        double mx = a[0];
        for (double v : a) {
            mx = Math.max(mx, v);
        }
        return mx;
    }

    private static int argMax(double[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) {
            if (a[i] > a[best]) {
                best = i;
            }
        }
        return best;
    }

    private static double meanBetween(double[] series, double[] time, double from, double to) {
        double s = 0.0;
        int c = 0;
        for (int i = 0; i < series.length; i++) {
            if (time[i] >= from && time[i] < to) {
                s += series[i];
                c++;
            }
        }
        return c == 0 ? Double.NaN : s / c;
    }

    private static double stdBetween(double[] series, double[] time, double from, double to) {
        double mu = meanBetween(series, time, from, to);
        if (Double.isNaN(mu)) {
            return Double.NaN;
        }
        double ss = 0.0;
        int c = 0;
        for (int i = 0; i < series.length; i++) {
            if (time[i] >= from && time[i] < to) {
                ss += (series[i] - mu) * (series[i] - mu);
                c++;
            }
        }
        return c < 2 ? 0.0 : Math.sqrt(ss / (c - 1));
    }

    private static double meanUpTo(double[] series, double[] time, double limit) {
        double s = 0.0;
        int c = 0;
        for (int i = 0; i < series.length; i++) {
            if (time[i] <= limit) {
                s += series[i];
                c++;
            }
        }
        return c == 0 ? Double.NaN : s / c;
    }

    private static double[] toArray(List<Double> list) {
        double[] a = new double[list.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = list.get(i);
        }
        return a;
    }
}
