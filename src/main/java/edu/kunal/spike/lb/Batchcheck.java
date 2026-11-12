package edu.kunal.spike.lb;

import java.nio.file.Paths;
import java.util.Arrays;

/** Batch-mode check: all jobs arrive at t = 0 on the 50-VM cluster; one assignment per algorithm. */
public final class Batchcheck {

    private static final int[][] VM_GROUPS = {
        {100, 7}, {500, 7}, {550, 6}, {1000, 6}, {1250, 6}, {1500, 6}, {1750, 6}, {2000, 6}
    };

    public static void main(String[] args) throws Exception {
        double[] mi = WorkloadGen.loadPool(Paths.get(args.length > 0 ? args[0] : "GoCJ_100.txt"));
        double[] mips = vmMips();
        int n = mi.length;
        int m = mips.length;

        double totalMi = 0, totalMips = 0, maxMi = 0, maxMips = 0;
        for (double v : mi) {
            totalMi += v;
            maxMi = Math.max(maxMi, v);
        }
        for (double v : mips) {
            totalMips += v;
            maxMips = Math.max(maxMips, v);
        }
        System.out.printf("%d jobs, %d VMs, total MI %.0f%n", n, m, totalMi);
        System.out.printf("Lower bounds: total MI / total MIPS = %.1f s, largest job on fastest VM = %.1f s%n",
                totalMi / totalMips, maxMi / maxMips);
        System.out.println("No algorithm can beat the larger of the two.\n");

        int[] rr = new int[n];
        for (int i = 0; i < n; i++) {
            rr[i] = i % m;
        }
        report("RR", rr, mi, mips);
        report("EFT (file order)", eft(order(mi, false), mi, mips), mi, mips);
        report("EFT (largest first)", eft(order(mi, true), mi, mips), mi, mips);
        report("Max-Min", completionTimeHeuristic(mi, mips, true), mi, mips);
        report("Min-Min", completionTimeHeuristic(mi, mips, false), mi, mips);
        report("DE-RALBA literal (no rebal.)", DeRalba.planBatch(mi, mips, new double[m], false).vmOf, mi, mips);
        report("DE-RALBA big-first (no rebal.)", DeRalba.planBatch(mi, mips, new double[m], true).vmOf, mi, mips);
        report("DE-RALBA author as-is", DeRalbaAuthor.run(new double[n], mi, mips).finalVm, mi, mips);
    }

    private static double[] vmMips() {
        int count = 0;
        for (int[] g : VM_GROUPS) {
            count += g[1];
        }
        double[] out = new double[count];
        int p = 0;
        for (int[] g : VM_GROUPS) {
            for (int k = 0; k < g[1]; k++) {
                out[p++] = g[0];
            }
        }
        return out;
    }

    private static int[] order(double[] mi, boolean largestFirst) {
        Integer[] idx = new Integer[mi.length];
        for (int i = 0; i < idx.length; i++) {
            idx[i] = i;
        }
        if (largestFirst) {
            Arrays.sort(idx, (a, b) -> Double.compare(mi[b], mi[a]));
        }
        int[] out = new int[idx.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = idx[i];
        }
        return out;
    }

    private static int[] eft(int[] order, double[] mi, double[] mips) {
        int[] vm = new int[mi.length];
        double[] ready = new double[mips.length];
        for (int i : order) {
            int best = 0;
            double bestT = Double.MAX_VALUE;
            for (int j = 0; j < mips.length; j++) {
                double t = ready[j] + mi[i] / mips[j];
                if (t < bestT) {
                    bestT = t;
                    best = j;
                }
            }
            vm[i] = best;
            ready[best] = bestT;
        }
        return vm;
    }

    /** Standard batch Max-Min / Min-Min on the completion-time matrix. */
    private static int[] completionTimeHeuristic(double[] mi, double[] mips, boolean maxMin) {
        int n = mi.length;
        int m = mips.length;
        int[] vm = new int[n];
        boolean[] done = new boolean[n];
        double[] ready = new double[m];
        for (int step = 0; step < n; step++) {
            int pick = -1;
            int pickVm = -1;
            double pickT = 0;
            for (int i = 0; i < n; i++) {
                if (done[i]) {
                    continue;
                }
                int bj = 0;
                double bt = Double.MAX_VALUE;
                for (int j = 0; j < m; j++) {
                    double t = ready[j] + mi[i] / mips[j];
                    if (t < bt) {
                        bt = t;
                        bj = j;
                    }
                }
                boolean better = pick == -1 || (maxMin ? bt > pickT : bt < pickT);
                if (better) {
                    pick = i;
                    pickVm = bj;
                    pickT = bt;
                }
            }
            done[pick] = true;
            vm[pick] = pickVm;
            ready[pickVm] = pickT;
        }
        return vm;
    }

    private static void report(String name, int[] vm, double[] mi, double[] mips) {
        double[] busy = new double[mips.length];
        for (int i = 0; i < mi.length; i++) {
            busy[vm[i]] += mi[i] / mips[vm[i]];
        }
        double max = 0, sum = 0;
        for (double b : busy) {
            max = Math.max(max, b);
            sum += b;
        }
        System.out.printf("%-32s makespan %9.1f s   ARUR %.3f%n", name, max, sum / busy.length / max);
    }
}