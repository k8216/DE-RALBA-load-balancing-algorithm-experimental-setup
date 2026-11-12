package edu.kunal.spike.lb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

// Plan-only test (no simulator): all 100 GoCJ jobs at time 0, in file order.
// Each flag switches on one behaviour seen in the repository's DRalba.java.
public class AuthorStyle {

    public static void main(String[] args) throws IOException {
        List<Double> sizes = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get("GoCJ_100.txt"))) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("JobID") || line.startsWith("ID")) {
                continue;
            }
            String[] p = line.split("\\s+|,");
            sizes.add(Double.parseDouble(p.length == 1 ? p[0] : p[1]));
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

        System.out.println("firstFit | eftPosOnly | reduceInEft | doubleReady | makespan | ARUR  | VMs used");
        run(jobs, mips, false, false, false, false);  // paper as I read it: must print 647.00 / 0.252
        run(jobs, mips, true,  false, false, false);
        run(jobs, mips, false, true,  false, false);
        run(jobs, mips, false, false, true,  false);
        run(jobs, mips, false, false, false, true);
        run(jobs, mips, false, true,  true,  false);
        run(jobs, mips, true,  true,  true,  false);
        run(jobs, mips, true,  true,  true,  true);   // everything as in the repository
    }

    static void run(double[] jobs, double[] mips, boolean firstFit, boolean eftPosOnly,
                    boolean reduceInEft, boolean doubleReady) {
        int m = mips.length;
        double totalMips = 0;
        double totalMi = 0;
        for (double v : mips) {
            totalMips += v;
        }
        for (double v : jobs) {
            totalMi += v;
        }

        double[] share = new double[m];
        double[] ready = new double[m];  // the scheduler's own ready time (inflated if doubleReady)
        double[] busy = new double[m];   // true work in seconds
        for (int j = 0; j < m; j++) {
            share[j] = totalMi * mips[j] / totalMips;
        }

        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < jobs.length; i++) {
            list.add(i);
        }

        while (!list.isEmpty()) {
            int top = 0;
            for (int j = 1; j < m; j++) {
                if (share[j] > share[top]) {
                    top = j;
                }
            }

            int pos = -1;
            if (firstFit) {
                // as pasted from the repository: first job in list order that fits;
                // the last list entry is never examined
                for (int k = 0; k < list.size() - 1; k++) {
                    if (jobs[list.get(k)] <= share[top]) {
                        pos = k;
                        break;
                    }
                }
            } else {
                for (int k = 0; k < list.size(); k++) {
                    if (jobs[list.get(k)] <= share[top]
                            && (pos == -1 || jobs[list.get(k)] > jobs[list.get(pos)])) {
                        pos = k;
                    }
                }
            }

            int vm = top;
            boolean shareStep = pos != -1;
            if (!shareStep) {
                pos = 0;
                for (int k = 1; k < list.size(); k++) {
                    if (jobs[list.get(k)] > jobs[list.get(pos)]) {
                        pos = k;
                    }
                }
                double len = jobs[list.get(pos)];
                double best = ready[top] + len / mips[top];
                for (int j = 0; j < m; j++) {
                    if (eftPosOnly && share[j] <= 0) {
                        continue;
                    }
                    double c = ready[j] + len / mips[j];
                    if (c < best || (c == best && j < vm)) {
                        best = c;
                        vm = j;
                    }
                }
            }

            int id = list.remove(pos);
            double exec = jobs[id] / mips[vm];
            busy[vm] += exec;
            ready[vm] = doubleReady ? ready[vm] + exec + ready[vm] : ready[vm] + exec;
            if (shareStep || reduceInEft) {
                share[vm] -= jobs[id];
            }
        }

        double max = 0;
        double sum = 0;
        int used = 0;
        for (double b : busy) {
            max = Math.max(max, b);
            sum += b;
            if (b > 0) {
                used++;
            }
        }
        System.out.println(String.format("%8b | %10b | %11b | %11b | %8.2f | %.3f | %d",
                firstFit, eftPosOnly, reduceInEft, doubleReady, max, (sum / m) / max, used));
    }
}