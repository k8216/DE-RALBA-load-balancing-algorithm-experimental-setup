package edu.kunal.spike.lb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Compare {

    public static void main(String[] args) throws IOException {
        List<Double> sizes = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("GoCJ_100.txt"))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("JobID") || line.startsWith("ID")) {
                continue;
            }
            String[] parts = line.split("\\s+|,");
            sizes.add(parts.length == 1 ? Double.parseDouble(parts[0]) : Double.parseDouble(parts[1]));
        }
        double[] jobs = sizes.stream().mapToDouble(Double::doubleValue).toArray();

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

        int n = jobs.length;
        Integer[] fileOrder = new Integer[n];
        for (int i = 0; i < n; i++) {
            fileOrder[i] = i;
        }
        Integer[] bigFirst = fileOrder.clone();
        Arrays.sort(bigFirst, (a, b) -> Double.compare(jobs[b], jobs[a]));

        System.out.println("jobs = " + n + ", VMs = " + m);
        System.out.println("method                          | makespan | ARUR  | VMs used | total busy (VM-s)");
        show("DE-RALBA literal", DeRalba.planBatch(jobs, mips, new double[m], false).vmCt);
        show("DE-RALBA BIG_FIRST variant", DeRalba.planBatch(jobs, mips, new double[m], true).vmCt);
        show("EFT, file order", eft(jobs, mips, fileOrder));
        show("EFT, biggest job first", eft(jobs, mips, bigFirst));
    }

    // EFT: take the jobs in the given order, each to the VM with the earliest completion time
    static double[] eft(double[] jobs, double[] mips, Integer[] order) {
        double[] ct = new double[mips.length];
        for (int idx : order) {
            int best = 0;
            double bestCt = Double.MAX_VALUE;
            for (int j = 0; j < mips.length; j++) {
                double c = jobs[idx] / mips[j] + ct[j];
                if (c < bestCt) {
                    bestCt = c;
                    best = j;
                }
            }
            ct[best] = bestCt;
        }
        return ct;
    }

    static void show(String label, double[] ct) {
        double max = 0, sum = 0;
        int used = 0;
        for (double c : ct) {
            max = Math.max(max, c);
            sum += c;
            if (c > 0) {
                used++;
            }
        }
        System.out.println(String.format("%-31s | %8.2f | %.3f | %8d | %10.1f",
                label, max, (sum / ct.length) / max, used, sum));
    }
}
