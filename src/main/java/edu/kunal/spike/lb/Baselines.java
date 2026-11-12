package edu.kunal.spike.lb;

/**
 * Baseline scheduling algorithms (Round Robin, Earliest Finish Time, Dynamic Max-Min)
 * as plain-Java models so they run without simulator overhead.
 *
 * Every algorithm returns an Assignment: which VM each job goes to and when it reaches that VM.
 */
public final class Baselines {

    private Baselines() {
    }

    public static final class Assignment {
        public final int[] vm;
        public final double[] dispatch;

        public Assignment(int[] vm, double[] dispatch) {
            this.vm = vm;
            this.dispatch = dispatch;
        }
    }

    /** Job i goes to VM (i mod m). Dispatches immediately on arrival. */
    public static Assignment roundRobin(double[] arrival, int vmCount) {
        int n = arrival.length;
        int[] vm = new int[n];
        double[] dispatch = new double[n];
        for (int i = 0; i < n; i++) {
            vm[i] = i % vmCount;
            dispatch[i] = arrival[i];
        }
        return new Assignment(vm, dispatch);
    }

    /**
     * Each job, in arrival order, goes to the VM where it would finish first,
     * given the work already assigned (same rule as eftMapper in SpikeLb).
     * Dispatches immediately on arrival.
     */
    public static Assignment eft(double[] arrival, double[] mi, double[] vmMips) {
        int n = arrival.length;
        int m = vmMips.length;
        int[] vm = new int[n];
        double[] dispatch = new double[n];
        double[] ready = new double[m];
        for (int i = 0; i < n; i++) {
            int best = 0;
            double bestFinish = Double.MAX_VALUE;
            for (int j = 0; j < m; j++) {
                double finish = Math.max(ready[j], arrival[i]) + mi[i] / vmMips[j];
                if (finish < bestFinish) {
                    bestFinish = finish;
                    best = j;
                }
            }
            ready[best] = bestFinish;
            vm[i] = best;
            dispatch[i] = arrival[i];
        }
        return new Assignment(vm, dispatch);
    }
/**
 * Max-Min in rounds of length T. Each round runs standard Max-Min on the jobs that arrived in it,
 * starting from every VM's current queue end (the "status table"). Same round timing as DE-RALBA.
 */
public static Assignment maxMinRounds(double[] arrival, double[] mi, double[] vmMips, double roundLen) {
    int n = arrival.length;
    int m = vmMips.length;
    int[] vm = new int[n];
    double[] dispatch = new double[n];
    double[] ready = new double[m];          // absolute time at which each VM's queue ends
    int i = 0;
    while (i < n) {
        double t = (Math.floor(arrival[i] / roundLen) + 1) * roundLen;
        int end = i;
        while (end < n && (Math.floor(arrival[end] / roundLen) + 1) * roundLen == t) {
            end++;
        }
        int size = end - i;
        boolean[] done = new boolean[size];
        for (int step = 0; step < size; step++) {
            int pick = -1;
            int pickVm = -1;
            double pickT = 0;
            for (int k = 0; k < size; k++) {
                if (done[k]) {
                    continue;
                }
                int bj = 0;
                double bt = Double.MAX_VALUE;
                for (int j = 0; j < m; j++) {
                    double c = Math.max(ready[j], t) + mi[i + k] / vmMips[j];
                    if (c < bt) {
                        bt = c;
                        bj = j;
                    }
                }
                if (pick == -1 || bt > pickT) {
                    pick = k;
                    pickVm = bj;
                    pickT = bt;
                }
            }
            done[pick] = true;
            vm[i + pick] = pickVm;
            dispatch[i + pick] = t;
            ready[pickVm] = pickT;
        }
        i = end;
    }
    return new Assignment(vm, dispatch);
}
    /**
     * Dynamic Max-Min algorithm operating over batching rounds of length roundLen.
     * In each round [t, t + roundLen):
     *   1. All jobs arriving in the round are collected into a batch.
     *   2. At round decision time decisionTime = (round + 1) * roundLen, while unassigned jobs remain:
     *      - For each candidate job, find the VM providing minimum completion time.
     *      - Select the job with the MAXIMUM minimum completion time (prioritizing large tasks).
     *      - Map that job to its best VM and update that VM's ready time.
     *   3. Dispatches jobs at decisionTime (matching the round-batching delay of DE-RALBA).
     */
    public static Assignment dynamicMaxMin(double[] arrival, double[] mi, double[] vmMips, double roundLen) {
        int n = arrival.length;
        int m = vmMips.length;
        int[] vm = new int[n];
        double[] dispatch = new double[n];
        double[] ready = new double[m];

        int i = 0;
        while (i < n) {
            long round = (long) Math.floor(arrival[i] / roundLen);
            double decisionTime = (round + 1) * roundLen;
            int end = i;
            while (end < n && (long) Math.floor(arrival[end] / roundLen) == round) {
                end++;
            }
            int batchSize = end - i;
            boolean[] done = new boolean[batchSize];
            int remaining = batchSize;

            while (remaining > 0) {
                int bestBatchIdx = -1;
                int bestVmForJob = -1;
                double maxMinCt = -1.0;

                for (int k = 0; k < batchSize; k++) {
                    if (done[k]) {
                        continue;
                    }
                    int jobIdx = i + k;
                    double len = mi[jobIdx];

                    // Find VM that finishes this job earliest
                    int bestVm = 0;
                    double minCt = Double.MAX_VALUE;
                    for (int j = 0; j < m; j++) {
                        double ct = Math.max(ready[j], decisionTime) + len / vmMips[j];
                        if (ct < minCt) {
                            minCt = ct;
                            bestVm = j;
                        }
                    }

                    // Max-Min selects the job with the maximum of these earliest finish times
                    if (minCt > maxMinCt) {
                        maxMinCt = minCt;
                        bestBatchIdx = k;
                        bestVmForJob = bestVm;
                    }
                }

                int chosenJobIdx = i + bestBatchIdx;
                vm[chosenJobIdx] = bestVmForJob;
                dispatch[chosenJobIdx] = decisionTime;
                ready[bestVmForJob] = maxMinCt;
                done[bestBatchIdx] = true;
                remaining--;
            }

            i = end;
        }

        return new Assignment(vm, dispatch);
    }
}
