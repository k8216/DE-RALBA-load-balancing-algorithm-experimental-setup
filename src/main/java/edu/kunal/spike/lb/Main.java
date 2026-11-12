
package edu.kunal.spike.lb;


import java.nio.file.*;
import java.util.*;
import java.util.function.*;

public class Main {

    static long[] loadLengths(String path) throws Exception {
        List<Long> l = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of(path))) {
            line = line.trim();
            if (line.isEmpty()) continue;
            try {
                l.add((long) Double.parseDouble(line.split("[,;\\s]+")[0]));
            } catch (NumberFormatException e) {
                // skip header or non-numeric lines
            }
        }
        if (l.isEmpty()) throw new IllegalStateException("No task lengths read from " + path);
        return l.stream().mapToLong(Long::longValue).toArray();
    }

    static long[] sample(long[] base, int n, long seed) {
        Random r = new Random(seed); long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = base[r.nextInt(base.length)];
        return out;
    }

    static LongFunction<List<Task>> scenario(long[] base, int n, double totalMips, double rho, double mult) {
        return seed -> {
            long[] len = sample(base, n, seed);
            double lam = ArrivalGenerator.lambdaFor(rho, totalMips, len);
            if (mult == 1) return ArrivalGenerator.steady(len, lam, seed);
            double horizon = n / lam;
            return ArrivalGenerator.spike(len, lam, mult, 0.3 * horizon, 0.1 * horizon, seed);
        };
    }

    public static void main(String[] args) throws Exception {
        long[] base = loadLengths(args.length > 0 ? args[0] : "GoCJ_Dataset_600.txt");
        int N = 2000, SEEDS = 10; double ROUND = 1.0;

        double[] mips = new double[15]; double[] opts = {500, 1000, 1500, 2000};
        Random mr = new Random(42);
        for (int i = 0; i < mips.length; i++) mips[i] = opts[mr.nextInt(opts.length)];
        double total = Arrays.stream(mips).sum();

        Map<String, LongFunction<List<Task>>> scenarios = new LinkedHashMap<>();
        scenarios.put("steady rho=0.5", scenario(base, N, total, 0.5, 1));
        scenarios.put("steady rho=0.8", scenario(base, N, total, 0.8, 1));
        scenarios.put("spike x2 (base 0.5)", scenario(base, N, total, 0.5, 2));
        scenarios.put("spike x4 (base 0.5)", scenario(base, N, total, 0.5, 4));

        Map<String, Supplier<Scheduler>> algos = new LinkedHashMap<>();
        algos.put("RR", RoundRobin::new);
        algos.put("EFT", Eft::new);
        algos.put("DynMaxMin", DynamicMaxMin::new);
        algos.put("DE-RALBA", DeRalba2::new);

        for (var sc : scenarios.entrySet()) {
            System.out.println("\n=== " + sc.getKey() + " ===");
            System.out.printf("%-10s %9s %9s %9s %9s %6s %8s %12s %8s%n",
                "algo", "meanResp", "p95", "p99", "makespan", "util", "imbal", "peakBacklog", "decis_ms");
            for (var al : algos.entrySet()) {
                double[] acc = new double[8];
                for (long seed = 1; seed <= SEEDS; seed++) {
                    Result r = CloudSimRunner.run(al.getValue().get(), sc.getValue().apply(seed), mips, ROUND);
                    double[] v = r.vec();
                    for (int i = 0; i < 8; i++) acc[i] += v[i] / SEEDS;
                }
                System.out.printf("%-10s %9.1f %9.1f %9.1f %9.1f %6.3f %8.3f %12.0f %8.2f%n",
                    al.getKey(), acc[0], acc[1], acc[2], acc[3], acc[4], acc[5], acc[6], acc[7]);
            }
        }
    }
}