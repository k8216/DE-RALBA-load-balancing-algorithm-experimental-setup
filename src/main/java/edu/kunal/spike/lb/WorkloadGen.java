package edu.kunal.spike.lb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

/**
 * Seeded workload generator for steady and bursty (spike) arrival scenarios.
 *
 * Arrivals follow a Poisson process whose rate is piecewise constant:
 *
 *     rate(t) = baseRate                      outside spike windows
 *     rate(t) = baseRate * spikeMultiplier    inside a spike window
 *
 *     baseRate = baseUtilization * totalMips / meanMi        [jobs per second]
 *
 * so "baseUtilization" (rho) is the nominal fraction of the cluster's total
 * MIPS that the offered load would use if work could be spread perfectly.
 * Real schedulers saturate earlier than rho = 1 because jobs are indivisible.
 *
 * Reproducibility: job sizes and arrival times use separate random streams
 * derived from the same seed. Two scenarios with the same seed and job count
 * therefore contain exactly the same jobs in the same order; only the arrival
 * times differ. Every algorithm must be run on the same Workload object.
 */
public final class WorkloadGen {

    private WorkloadGen() {
    }

    public static final String PRE = "pre";
    public static final String SPIKE = "spike";
    public static final String AFTER = "after";

    /** Half-open time window [start, end). */
    public static final class Window {
        public final double start;
        public final double end;

        public Window(double start, double end) {
            this.start = start;
            this.end = end;
        }
    }

    public enum SizeMode {
        /** Shuffle the pool and take jobs in order, reshuffling when the pool runs out. Keeps the exact job mix. */
        SHUFFLE_CYCLES,
        /** Draw each job size independently with replacement (adds size variance between seeds). */
        BOOTSTRAP
    }

    public static final class Config {
        public String name = "steady";
        public int jobs = 1000;
        /** Sum of all VM MIPS in the cluster. */
        public double totalMips = 1.0;
        /** Nominal utilization rho outside spikes (0.5 means half the cluster capacity). */
        public double baseUtilization = 0.5;
        /** Arrival-rate multiplier inside a spike. 1.0 means no spike. */
        public double spikeMultiplier = 1.0;
        /** Start time of the first spike, in seconds. */
        public double spikeStart = 0.0;
        public double spikeDuration = 0.0;
        /** 0 = a single spike. Greater than 0 = a spike every spikePeriod seconds (repeated bursts). */
        public double spikePeriod = 0.0;
        public SizeMode sizeMode = SizeMode.SHUFFLE_CYCLES;

        public boolean hasSpike() {
            return spikeMultiplier != 1.0 && spikeDuration > 0.0;
        }

        /** Throws IllegalArgumentException for settings that would give a meaningless workload. */
        public void validate() {
            if (jobs <= 0) {
                throw new IllegalArgumentException("jobs must be positive, got " + jobs);
            }
            requirePositive("totalMips", totalMips);
            requirePositive("baseUtilization", baseUtilization);
            requirePositive("spikeMultiplier", spikeMultiplier);
            requireNonNegative("spikeStart", spikeStart);
            requireNonNegative("spikeDuration", spikeDuration);
            requireNonNegative("spikePeriod", spikePeriod);
            if (spikePeriod > 0 && spikeDuration >= spikePeriod) {
                throw new IllegalArgumentException("spikeDuration (" + spikeDuration
                        + ") must be shorter than spikePeriod (" + spikePeriod + ") so bursts have gaps between them");
            }
            if (sizeMode == null) {
                throw new IllegalArgumentException("sizeMode must not be null");
            }
        }

        private static void requirePositive(String name, double v) {
            if (!Double.isFinite(v) || v <= 0) {
                throw new IllegalArgumentException(name + " must be finite and > 0, got " + v);
            }
        }

        private static void requireNonNegative(String name, double v) {
            if (!Double.isFinite(v) || v < 0) {
                throw new IllegalArgumentException(name + " must be finite and >= 0, got " + v);
            }
        }

        public static Config steady(String name, int jobs, double totalMips, double rho) {
            Config c = new Config();
            c.name = name;
            c.jobs = jobs;
            c.totalMips = totalMips;
            c.baseUtilization = rho;
            return c;
        }

        public static Config spike(String name, int jobs, double totalMips, double rho,
                                   double multiplier, double start, double duration) {
            Config c = steady(name, jobs, totalMips, rho);
            c.spikeMultiplier = multiplier;
            c.spikeStart = start;
            c.spikeDuration = duration;
            return c;
        }

        public static Config bursts(String name, int jobs, double totalMips, double rho,
                                    double multiplier, double start, double duration, double period) {
            Config c = spike(name, jobs, totalMips, rho, multiplier, start, duration);
            c.spikePeriod = period;
            return c;
        }
    }

    public static final class Workload {
        public final Config config;
        public final long seed;
        public final double[] mi;
        public final double[] arrival;
        /** Spike windows that start before the last arrival. Empty for a steady workload. */
        public final List<Window> spikes;
        /** Arrival rate outside spikes, jobs per second. */
        public final double baseRate;

        Workload(Config config, long seed, double[] mi, double[] arrival, List<Window> spikes, double baseRate) {
            this.config = config;
            this.seed = seed;
            this.mi = mi;
            this.arrival = arrival;
            this.spikes = spikes;
            this.baseRate = baseRate;
        }

        public double lastArrival() {
            return arrival.length == 0 ? 0.0 : arrival[arrival.length - 1];
        }

        /**
         * Columns that describe the generated workload, for the summary CSV. Report them next to the
         * results: rho_config is the target, rho_realized_* is what this seed actually offered per phase.
         * Neither is the utilization the cluster achieves; that depends on the scheduler.
         */
        public LinkedHashMap<String, Object> describe() {
            LinkedHashMap<String, Object> d = new LinkedHashMap<>();
            d.put("scenario", config.name);
            d.put("seed", seed);

            // WG-1: Put the key workload facts into describe()
            d.put("jobs", config.jobs);
            d.put("size_mode", config.sizeMode.name());
            d.put("last_arrival_s_generated", lastArrival());
            d.put("expected_spike_arrivals",
                    config.hasSpike() ? baseRate * config.spikeMultiplier * config.spikeDuration : 0.0);

            d.put("rho_config", config.baseUtilization);
            d.put("spike_multiplier", config.spikeMultiplier);
            d.put("spike_start_s", config.spikeStart);
            d.put("spike_duration_s", config.spikeDuration);
            d.put("spike_period_s", config.spikePeriod);
            d.put("base_rate_jobs_per_s", baseRate);
            d.put("rho_realized_pre", realizedUtilization(PRE));
            d.put("rho_realized_spike", realizedUtilization(SPIKE));
            d.put("rho_realized_after", realizedUtilization(AFTER));
            return d;
        }

        public String phaseOf(double t) {
            return WorkloadGen.phaseOf(spikes, t);
        }

        /** Seconds of the arrival period [0, lastArrival] that fall in the phase. */
        public double phaseDuration(String phase) {
            double horizon = lastArrival();
            if (spikes.isEmpty()) {
                return PRE.equals(phase) ? horizon : 0.0;
            }
            double pre = Math.min(spikes.get(0).start, horizon);
            double inSpike = 0.0;
            for (Window w : spikes) {
                inSpike += Math.max(0.0, Math.min(w.end, horizon) - w.start);
            }
            switch (phase) {
                case PRE:
                    return pre;
                case SPIKE:
                    return inSpike;
                default:
                    return Math.max(0.0, horizon - pre - inSpike);
            }
        }

        /** Offered load in the phase as a fraction of totalMips (NaN if the phase has zero length). */
        public double realizedUtilization(String phase) {
            double dur = phaseDuration(phase);
            if (dur <= 0) {
                return Double.NaN;
            }
            double sum = 0.0;
            for (int i = 0; i < mi.length; i++) {
                if (phaseOf(arrival[i]).equals(phase)) {
                    sum += mi[i];
                }
            }
            return sum / (dur * config.totalMips);
        }
    }

    public static String phaseOf(List<Window> spikes, double t) {
        if (spikes.isEmpty()) {
            return PRE;
        }
        for (Window w : spikes) {
            if (t >= w.start && t < w.end) {
                return SPIKE;
            }
        }
        return t < spikes.get(0).start ? PRE : AFTER;
    }

    /**
     * Reads job lengths from a GoCJ-style file: one number per line, or "id number" / "id,number".
     * Blank lines, comments and header lines (starting with a letter or '#') are skipped.
     */
    public static double[] loadPool(Path file) throws IOException {
        List<Double> values = new ArrayList<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || Character.isLetter(line.charAt(0))) {
                continue;
            }
            String[] parts = line.split("\\s+|,");
            values.add(Double.parseDouble(parts.length == 1 ? parts[0] : parts[1]));
        }
        double[] out = new double[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    public static Workload generate(Config cfg, double[] pool, long seed) {
        if (pool == null || pool.length == 0) {
            throw new IllegalArgumentException("Job-size pool is empty.");
        }
        for (int i = 0; i < pool.length; i++) {
            if (!Double.isFinite(pool[i]) || pool[i] <= 0) {
                throw new IllegalArgumentException("Job size at pool index " + i + " must be finite and > 0, got " + pool[i]);
            }
        }
        cfg.validate();

        Random sizeRng = new Random(seed ^ 0x9E3779B97F4A7C15L);
        Random arrivalRng = new Random(seed);

        double[] mi = sizes(cfg, pool, sizeRng);

        double poolMean = 0.0;
        for (double v : pool) {
            poolMean += v;
        }
        poolMean /= pool.length;
        double baseRate = cfg.baseUtilization * cfg.totalMips / poolMean;

        if (!Double.isFinite(baseRate) || baseRate <= 0) {
            throw new IllegalArgumentException("Computed arrival rate is not usable: " + baseRate);
        }

        double[] arrival = new double[cfg.jobs];
        double t = 0.0;
        long steps = 0;
        for (int i = 0; i < cfg.jobs; i++) {
            while (true) {
                if (++steps > 100_000_000L) {
                    throw new IllegalStateException("Arrival generation did not finish; check the spike settings.");
                }
                double rate = baseRate * (inSpike(cfg, t) ? cfg.spikeMultiplier : 1.0);
                double boundary = nextBoundary(cfg, t);
                double gap = -Math.log(1.0 - arrivalRng.nextDouble()) / rate;
                if (!Double.isFinite(gap)) {
                    throw new IllegalStateException("Non-finite interarrival gap at t=" + t);
                }
                if (t + gap < boundary) {
                    t += gap;
                    break;
                }
                // The rate changes before the next arrival. The exponential gap is memoryless,
                // so restart the draw from the boundary with the new rate.
                t = boundary;
            }
            arrival[i] = t;
        }

        List<Window> windows = new ArrayList<>();
        if (cfg.hasSpike()) {
            double last = arrival[arrival.length - 1];
            if (cfg.spikePeriod <= 0) {
                if (cfg.spikeStart < last) {
                    windows.add(new Window(cfg.spikeStart, cfg.spikeStart + cfg.spikeDuration));
                }
            } else {
                for (int k = 0; cfg.spikeStart + k * cfg.spikePeriod < last; k++) {
                    double s = cfg.spikeStart + k * cfg.spikePeriod;
                    windows.add(new Window(s, s + cfg.spikeDuration));
                }
            }
        }
        return new Workload(cfg, seed, mi, arrival, Collections.unmodifiableList(windows), baseRate);
    }

    private static double[] sizes(Config cfg, double[] pool, Random rng) {
        double[] mi = new double[cfg.jobs];
        if (cfg.sizeMode == SizeMode.BOOTSTRAP) {
            for (int i = 0; i < mi.length; i++) {
                mi[i] = pool[rng.nextInt(pool.length)];
            }
            return mi;
        }
        List<Double> deck = new ArrayList<>();
        int i = 0;
        while (i < mi.length) {
            if (deck.isEmpty()) {
                for (double v : pool) {
                    deck.add(v);
                }
                Collections.shuffle(deck, rng);
            }
            mi[i++] = deck.remove(deck.size() - 1);
        }
        return mi;
    }

    private static boolean inSpike(Config c, double t) {
        if (!c.hasSpike() || t < c.spikeStart) {
            return false;
        }
        if (c.spikePeriod <= 0) {
            return t < c.spikeStart + c.spikeDuration;
        }
        double k = Math.floor((t - c.spikeStart) / c.spikePeriod);
        double ws = c.spikeStart + k * c.spikePeriod;
        return t >= ws && t < ws + c.spikeDuration;
    }

    /** Next time strictly after t at which the arrival rate changes (infinity if never). */
    private static double nextBoundary(Config c, double t) {
        if (!c.hasSpike()) {
            return Double.POSITIVE_INFINITY;
        }
        double b;
        if (t < c.spikeStart) {
            b = c.spikeStart;
        } else if (c.spikePeriod <= 0) {
            double end = c.spikeStart + c.spikeDuration;
            b = t < end ? end : Double.POSITIVE_INFINITY;
        } else {
            double k = Math.floor((t - c.spikeStart) / c.spikePeriod);
            double ws = c.spikeStart + k * c.spikePeriod;
            double we = ws + c.spikeDuration;
            b = t < we ? we : ws + c.spikePeriod;
        }
        return b <= t ? Math.nextUp(t) : b;
    }
}
