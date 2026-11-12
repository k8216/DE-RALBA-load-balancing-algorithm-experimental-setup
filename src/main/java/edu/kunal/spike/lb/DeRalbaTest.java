package edu.kunal.spike.lb;

import java.util.Arrays;
import java.util.TreeMap;

public class DeRalbaTest {

    public static void main(String[] args) {
        int[][] groups = {
            {100, 7}, {500, 7}, {550, 6}, {1000, 6}, {1250, 6}, {1500, 6}, {1750, 6}, {2000, 6}
        };
        int m = 0;
        for (int[] g : groups) {
            m += g[1];
        }
        double[] mips = new double[m];
        int idx = 0;
        for (int[] g : groups) {
            for (int k = 0; k < g[1]; k++) {
                mips[idx++] = g[0];
            }
        }

        double[] jobs = new double[100];
        Arrays.fill(jobs, 10_000);

        DeRalba.Plan plan = DeRalba.planBatch(jobs, mips, new double[m]);

        System.out.println("Share-phase jobs : " + plan.sharePhaseJobs);
        System.out.println("EFT-phase jobs   : " + plan.eftPhaseJobs);

        int[] jobsOnVm = new int[m];
        for (int v : plan.vmOf) {
            jobsOnVm[v]++;
        }
        TreeMap<Integer, int[]> bySpeed = new TreeMap<>(); // speed -> {VM count, job count}
        for (int j = 0; j < m; j++) {
            int[] row = bySpeed.computeIfAbsent((int) mips[j], k -> new int[2]);
            row[0]++;
            row[1] += jobsOnVm[j];
        }
        System.out.println("speed | VMs | total jobs");
        for (var e : bySpeed.entrySet()) {
            System.out.println(String.format("%5d | %3d | %3d",
                    e.getKey(), e.getValue()[0], e.getValue()[1]));
        }

        double max = 0, sum = 0;
        for (double ct : plan.vmCt) {
            max = Math.max(max, ct);
            sum += ct;
        }
        System.out.println(String.format("Makespan (max VM_CT) : %.2f s", max));
        System.out.println(String.format("ARUR (paper Eq. 13)  : %.3f", (sum / m) / max));
    }
}