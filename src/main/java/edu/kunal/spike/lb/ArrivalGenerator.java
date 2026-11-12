
package edu.kunal.spike.lb;


import java.util.*;
import java.util.function.DoubleUnaryOperator;

public class ArrivalGenerator {
    /** lambda (tasks/sec) that gives utilization rho on a cluster with totalMips. */
    public static double lambdaFor(double rho, double totalMips, long[] lengths) {
        return rho * totalMips / Arrays.stream(lengths).average().orElseThrow();
    }

    public static List<Task> steady(long[] lengths, double lambda, long seed) {
        return generate(lengths, t -> lambda, lambda, seed);
    }

    public static List<Task> spike(long[] lengths, double lambda, double mult,
                                   double start, double dur, long seed) {
        return generate(lengths,
            t -> (t >= start && t < start + dur) ? lambda * mult : lambda,
            lambda * mult, seed);
    }

    // non-homogeneous Poisson process via thinning
    private static List<Task> generate(long[] lengths, DoubleUnaryOperator rate, double lamMax, long seed) {
        Random rng = new Random(seed);
        List<Task> tasks = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < lengths.length; i++) {
            do { t += -Math.log(1 - rng.nextDouble()) / lamMax; }
            while (rng.nextDouble() > rate.applyAsDouble(t) / lamMax);
            tasks.add(new Task(i, lengths[i], t));
        }
        return tasks;
    }
}