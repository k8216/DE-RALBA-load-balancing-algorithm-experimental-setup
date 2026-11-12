package edu.kunal.spike.lb;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Timeline replica of the author's DRalba.java, bugs included (see the assumptions in the paper notes).
 * Returns the same Result as DeRalbaDynamic: finalVm = VM the job actually runs on, dispatchAt = tick time.
 */
public final class DeRalbaAuthor {

    public static final double TICK = 1.0;
    private static final double EPS = 1e-9;

    private static final class Vt {            // the author's VMStatusTable
        final double mips;
        final double ratio;
        double share, ready, totalAssigned, executed, remaining, lastUpdated;
        final List<Integer> cloudlets = new ArrayList<>();

        Vt(double mips, double ratio) {
            this.mips = mips;
            this.ratio = ratio;
        }
    }

    private final double[] mi;
    private final Vt[] vt;
    private final List<Integer> pending = new ArrayList<>();   // the author's cloudletlist
    private final double[] finish;
    private final double[] cursor;
    private final PriorityQueue<double[]> finishEvents = new PriorityQueue<>(
            (a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
    private boolean arrivedFlag = false;                        // dynamicCloudletArrived
    private DeRalbaDynamic.Result res;

    private DeRalbaAuthor(double[] mi, double[] vmMips) {
        this.mi = mi;
        double total = 0;
        for (double v : vmMips) {
            total += v;
        }
        vt = new Vt[vmMips.length];
        for (int j = 0; j < vt.length; j++) {
            vt[j] = new Vt(vmMips[j], vmMips[j] / total);
        }
        finish = new double[mi.length];
        Arrays.fill(finish, Double.POSITIVE_INFINITY);          // not dispatched = not finished
        cursor = new double[vmMips.length];
    }

    public static DeRalbaDynamic.Result run(double[] arrival, double[] mi, double[] vmMips) {
        return new DeRalbaAuthor(mi, vmMips).simulate(arrival);
    }

    private static double tickOf(double a) {
        return Math.max(0.0, Math.ceil(a / TICK - 1e-9)) * TICK;
    }

    private DeRalbaDynamic.Result simulate(double[] arrival) {
        int n = mi.length;
        res = new DeRalbaDynamic.Result();
        res.finalVm = new int[n];
        res.dispatchAt = new double[n];
        int next = 0;
        while (next < n || !finishEvents.isEmpty()) {
            double tickT = next < n ? tickOf(arrival[next]) : Double.MAX_VALUE;
            double finT = finishEvents.isEmpty() ? Double.MAX_VALUE : finishEvents.peek()[0];
            if (tickT <= finT) {                                // arrival tick first on ties
                List<Integer> batch = new ArrayList<>();
                while (next < n && tickOf(arrival[next]) == tickT) {
                    batch.add(next++);
                }
                decide(tickT, batch);
            } else {
                double[] e = finishEvents.poll();
                onFinish((int) e[1], e[0]);
            }
        }
        double mk = 0;
        for (double f : finish) {
            mk = Math.max(mk, f);
        }
        res.predictedMakespan = mk;
        return res;
    }

    // createRandomCloudlets + filler()
    private void decide(double t, List<Integer> batch) {
        if (t > 0) {
            arrivedFlag = true;
        }
        pending.addAll(batch);
        if (t <= 0) {
            calculateShares();                                  // done once at start-up in the author's code
        }
        List<Integer> placed = new ArrayList<>();
        while (!pending.isEmpty()) {
            int v = maxShareVm();
            int c = firstFitForShare(vt[v]);
            if (c != -1) {
                assign(vt[v], c);
                res.finalVm[c] = v;
                placed.add(c);
                res.shareJobs++;
            } else if (arrivedFlag) {
                updateAll((int) t);
                arrivedFlag = false;
            } else {
                c = largestPending();
                v = eftVm(c);
                assign(vt[v], c);
                res.finalVm[c] = v;
                placed.add(c);
                res.eftJobs++;
            }
        }
        placed.sort(Integer::compare);                          // broker submits in job order
        for (int c : placed) {
            int j = res.finalVm[c];
            double start = Math.max(cursor[j], t);
            finish[c] = start + mi[c] / vt[j].mips;
            cursor[j] = finish[c];
            res.dispatchAt[c] = t;
            finishEvents.add(new double[] {finish[c], c});
        }
        res.rounds++;
    }

    // onCloudletFinishListener
    private void onFinish(int job, double time) {
        Vt v = vt[res.finalVm[job]];
        updateOne(v, (int) time);
        if (v.ready <= 0) {
            Vt heavy = largestReady();
            List<Integer> list = heavy.cloudlets;
            for (int i = 0; i < list.size(); i++) {
                int c = list.get(i);
                if (finish[c] > time + EPS) {                   // !isFinished()
                    double onCurrent = mi[c] / v.mips + v.ready;
                    double onHeavy = mi[c] / heavy.mips + heavy.ready;
                    if (onCurrent < onHeavy) {
                        assign(v, c);                           // bookkeeping only: the job keeps running where it is
                        heavy.ready -= onHeavy;
                        heavy.totalAssigned -= mi[c];
                        heavy.cloudlets.remove(Integer.valueOf(c));
                        res.migrations++;
                    }
                }
            }
            updateAll((int) time);
        }
    }

    private void assign(Vt v, int c) {                          // assignCloudlettoVM
        v.share -= mi[c];
        v.totalAssigned += mi[c];
        v.remaining += mi[c];
        v.ready = v.ready + (mi[c] / v.mips + v.ready);         // the author's doubling of ready time
        v.cloudlets.add(c);
        pending.remove(Integer.valueOf(c));
    }

    private void updateOne(Vt v, int clock) {                   // updateVMStatusTable(clock, table)
        double diff = clock - v.lastUpdated;
        if (v.ready - diff < 0) {
            v.ready = 0;
            v.remaining = 0;
            v.executed = v.totalAssigned;
        } else {
            double executedMi = v.mips * clock;
            v.ready -= diff;
            v.executed = executedMi;
            v.remaining = v.totalAssigned - executedMi;
        }
        v.lastUpdated = clock;
    }

    private void updateAll(int clock) {                         // updateVMStatusTable(clock)
        for (Vt v : vt) {
            updateOne(v, clock);
        }
        calculateShares();
    }

    private void calculateShares() {
        double total = 0;
        for (int c : pending) {
            total += mi[c];
        }
        for (Vt v : vt) {
            total += v.remaining;
        }
        for (Vt v : vt) {
            v.share = v.ratio * total - v.remaining;
        }
    }

    private int maxShareVm() {
        int best = 0;
        for (int j = 1; j < vt.length; j++) {
            if (vt[j].share > vt[best].share) {
                best = j;
            }
        }
        return best;
    }

    // getMaxcloudletForVmShare: first fit, last list entry never examined
    private int firstFitForShare(Vt v) {
        for (int i = 0; i < pending.size() - 1; i++) {
            if (mi[pending.get(i)] <= v.share) {
                return pending.get(i);
            }
        }
        return -1;
    }

    private int largestPending() {
        int best = pending.get(0);
        for (int i = 1; i < pending.size(); i++) {
            if (mi[pending.get(i)] > mi[best]) {
                best = pending.get(i);
            }
        }
        return best;
    }

    // getVmWithMinFinishTime
    private int eftVm(int c) {
        int best = maxShareVm();
        double bestT = vt[best].ready + mi[c] / vt[best].mips;
        for (int j = 0; j < vt.length; j++) {
            if (vt[j].share > 0) {
                double t = vt[j].ready + mi[c] / vt[j].mips;
                if (bestT > t) {
                    bestT = t;
                    best = j;
                }
            }
        }
        return best;
    }

    private Vt largestReady() {
        Vt best = vt[0];
        for (Vt v : vt) {
            if (v.ready > best.ready) {
                best = v;
            }
        }
        return best;
    }
}