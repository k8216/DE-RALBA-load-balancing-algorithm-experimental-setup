package edu.kunal.spike.lb;

import java.util.ArrayList;
import java.util.List;

// DE-RALBA as a timeline model: rounds, Update-Scheduler, share step, EFT step,
// empty-VM interrupt and rebalancing. The result (final VM and dispatch time of
// every job) is then executed by CloudSim Plus (SpikeLb) or by QueueExec (ExperimentRunner).
public class DeRalbaDynamic {

    private static final double EPS = 1e-9;

    // One row of the paper's vStatusTable, plus the VM's FIFO queue
    static class VmStatus {
        final double mips;
        double vmShare;
        double executedMI;
        double totalAssignedMI;
        double remMI;
        double readyTime;
        double lastUpdateTime;
        final List<Integer> queue = new ArrayList<>();     // job indices, FIFO
        final List<Double> dispatch = new ArrayList<>();   // time each job reached this VM

        VmStatus(double mips) {
            this.mips = mips;
        }
    }

    public static class Result {
        public int[] finalVm;
        public double[] dispatchAt;
        public int migrations;
        public int shareJobs;
        public int eftJobs;
        public int rounds;
        public double predictedMakespan;
    }

    private final double[] mi;
    private final VmStatus[] vm;
    private final boolean rebalance;
    private final DeRalba.Options opts;
    private final double[] handled;

    private DeRalbaDynamic(double[] mi, double[] vmMips, boolean rebalance, DeRalba.Options opts) {
        this.mi = mi;
        this.rebalance = rebalance;
        this.opts = opts;
        this.vm = new VmStatus[vmMips.length];
        for (int j = 0; j < vmMips.length; j++) {
            vm[j] = new VmStatus(vmMips[j]);
        }
        this.handled = new double[vmMips.length];
        java.util.Arrays.fill(handled, -1);
    }

    /** Uses the default variant set by DeRalba.BIG_FIRST (keeps SpikeLb working unchanged). */
    public static Result run(double[] arrival, double[] mi, double[] vmMips,
                             double roundLen, boolean rebalance) {
        return run(arrival, mi, vmMips, roundLen, rebalance, DeRalba.BIG_FIRST);
    }

    /** bigFirst = true: BIG_FIRST variant; false: literal pseudocode. */
    public static Result run(double[] arrival, double[] mi, double[] vmMips,
                             double roundLen, boolean rebalance, boolean bigFirst) {
        return run(arrival, mi, vmMips, roundLen, rebalance,
                bigFirst ? DeRalba.Options.BIGFIRST : DeRalba.Options.LITERAL);
    }

    /** Full control over the variant. */
    public static Result run(double[] arrival, double[] mi, double[] vmMips,
                             double roundLen, boolean rebalance, DeRalba.Options opts) {
        return new DeRalbaDynamic(mi, vmMips, rebalance, opts).simulate(arrival, roundLen);
    }

    // start and finish time of each job in a VM's queue (one job at a time, FIFO)
    private double[][] timeline(VmStatus v) {
        int k = v.queue.size();
        double[] start = new double[k];
        double[] finish = new double[k];
        double cursor = 0;
        for (int p = 0; p < k; p++) {
            start[p] = Math.max(cursor, v.dispatch.get(p));
            finish[p] = start[p] + mi[v.queue.get(p)] / v.mips;
            cursor = finish[p];
        }
        return new double[][] {start, finish};
    }

    private double lastFinish(VmStatus v) {
        if (v.queue.isEmpty()) {
            return 0;
        }
        return timeline(v)[1][v.queue.size() - 1];
    }

    private boolean isEmpty(VmStatus v, double t) {
        return v.queue.isEmpty() || lastFinish(v) <= t + EPS;
    }

    // position of the smallest job that has not started by time t, or -1
    private int smallestWaiting(VmStatus v, double t) {
        double[][] tl = timeline(v);
        int pos = -1;
        for (int p = 0; p < v.queue.size(); p++) {
            if (tl[0][p] > t + EPS && (pos == -1 || mi[v.queue.get(p)] < mi[v.queue.get(pos)])) {
                pos = p;
            }
        }
        return pos;
    }

    // Algorithm 2 (Update-Scheduler): refresh every VM's row at time t
    private void updateScheduler(double t) {
        for (VmStatus v : vm) {
            double[][] tl = timeline(v);
            double executed = 0;
            double assigned = 0;
            for (int p = 0; p < v.queue.size(); p++) {
                double size = mi[v.queue.get(p)];
                assigned += size;
                if (tl[1][p] <= t + EPS) {
                    executed += size;
                } else if (tl[0][p] < t) {
                    executed += (t - tl[0][p]) * v.mips;
                }
            }
            v.totalAssignedMI = assigned;
            v.executedMI = executed;
            v.remMI = assigned - executed;
            v.readyTime = Math.max(t, lastFinish(v));
            v.lastUpdateTime = t;
        }
    }

    // One scheduling round (Algorithm 1, lines 9-32) for the jobs in 'batch'
    private void decide(double t, List<Integer> batch, Result r) {
        updateScheduler(t);
        int n = batch.size();
        int m = vm.length;
        double[] jobMi = new double[n];
        double tLength = 0;
        for (int k = 0; k < n; k++) {
            jobMi[k] = mi[batch.get(k)];
            tLength += jobMi[k];
        }
        double totalMips = 0;
        for (VmStatus v : vm) {
            totalMips += v.mips;
        }
        double[] mips = new double[m];
        double[] backlog = new double[m];
        for (int j = 0; j < m; j++) {
            mips[j] = vm[j].mips;
            vm[j].vmShare = tLength * (mips[j] / totalMips);   // lines 12-15
            backlog[j] = Math.max(0, vm[j].readyTime - t);     // queued seconds = queued MI / MIPS
        }
        DeRalba.Plan plan = DeRalba.planBatch(jobMi, mips, backlog, opts);
        r.shareJobs += plan.sharePhaseJobs;
        r.eftJobs += plan.eftPhaseJobs;
        r.rounds++;
        for (int j = 0; j < m; j++) {
            for (int k = 0; k < n; k++) {            // batch is in job-id order
                if (plan.vmOf[k] == j) {
                    int job = batch.get(k);
                    vm[j].queue.add(job);
                    vm[j].dispatch.add(t);
                    r.finalVm[job] = j;
                    r.dispatchAt[job] = t;
                }
            }
        }
        updateScheduler(t);
    }

    // earliest time at which some VM has just run out of work and was not handled yet
    private double nextEmptyEvent() {
        double best = Double.MAX_VALUE;
        for (int j = 0; j < vm.length; j++) {
            if (vm[j].queue.isEmpty()) {
                continue;
            }
            double f = lastFinish(vm[j]);
            if (f > handled[j] + EPS && f < best) {
                best = f;
            }
        }
        return best;
    }

    // Empty-VM interrupt at time e and rebalancing (lines 33-45)
    private void handleEmpty(double e, Result r) {
        for (int j = 0; j < vm.length; j++) {
            if (!vm[j].queue.isEmpty()) {
                double f = lastFinish(vm[j]);
                if (f <= e + EPS && f > handled[j] + EPS) {
                    handled[j] = f;
                }
            }
        }
        if (!rebalance) {
            return;
        }
        while (true) {
            updateScheduler(e);
            int empty = -1;
            for (int j = 0; j < vm.length; j++) {
                if (isEmpty(vm[j], e) && (empty == -1 || vm[j].mips > vm[empty].mips)) {
                    empty = j;
                }
            }
            if (empty == -1) {
                return;
            }

            // most loaded VM first
            Integer[] order = java.util.stream.IntStream.range(0, vm.length).boxed()
                    .sorted((a, b) -> Double.compare(vm[b].readyTime, vm[a].readyTime))
                    .toArray(Integer[]::new);

            boolean moved = false;
            for (int li : order) {
                VmStatus lv = vm[li];
                double[][] tl = timeline(lv);

                List<Integer> waiting = new ArrayList<>();
                for (int p = 0; p < lv.queue.size(); p++) {
                    if (tl[0][p] > e + EPS) {
                        waiting.add(p);
                    }
                }
                // minCld first, as in the paper
                waiting.sort((a, b) -> Double.compare(mi[lv.queue.get(a)], mi[lv.queue.get(b)]));

                for (int pos : waiting) {
                    int job = lv.queue.get(pos);
                    double finishOnEmpty = e + mi[job] / vm[empty].mips;
                    if (finishOnEmpty < tl[1][pos] - EPS) {
                        // QE-3: Guarantee that migrated job has NOT started yet and dispatch = migration time
                        if (tl[0][pos] <= e + EPS) {
                            throw new IllegalStateException("Attempted to migrate job " + job
                                    + " which already started at " + tl[0][pos] + " <= " + e);
                        }
                        lv.queue.remove(pos);
                        lv.dispatch.remove(pos);
                        vm[empty].queue.add(job);
                        vm[empty].dispatch.add(e);
                        r.finalVm[job] = empty;
                        r.dispatchAt[job] = e;
                        r.migrations++;
                        moved = true;
                        break;
                    }
                }
                if (moved) {
                    break;
                }
            }
            if (!moved) {
                return;
            }
        }
    }

    private Result simulate(double[] arrival, double roundLen) {
        int n = mi.length;
        Result r = new Result();
        r.finalVm = new int[n];
        r.dispatchAt = new double[n];
        int next = 0;
        while (true) {
            double decision = Double.MAX_VALUE;
            if (next < n) {
                decision = (Math.floor(arrival[next] / roundLen) + 1) * roundLen;
            }
            double empty = nextEmptyEvent();
            if (decision == Double.MAX_VALUE && empty == Double.MAX_VALUE) {
                break;
            }
            if (decision <= empty) {
                List<Integer> batch = new ArrayList<>();
                while (next < n && (Math.floor(arrival[next] / roundLen) + 1) * roundLen == decision) {
                    batch.add(next);
                    next++;
                }
                decide(decision, batch, r);
            } else {
                handleEmpty(empty, r);
            }
        }
        double mk = 0;
        for (VmStatus v : vm) {
            mk = Math.max(mk, lastFinish(v));
        }
        r.predictedMakespan = mk;
        return r;
    }
}
