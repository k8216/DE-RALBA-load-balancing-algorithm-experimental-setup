package edu.kunal.spike.lb;

import java.util.ArrayList;
import java.util.List;

public class DeRalba {

    // false = paper-style core scheduling
    // true  = experimental BIG_FIRST variant (used only by SpikeLb's default path)
    public static final boolean BIG_FIRST = true;

    /** Variant switches for planBatch. */
    public static final class Options {
        public final String label;
        public final boolean bigFirst;
        /** false = paper: share = ratio * batchMI.  true = share = ratio * (batchMI + queuedMI) - queuedMI. */
        public final boolean backlogAwareShare;

        public Options(String label, boolean bigFirst, boolean backlogAwareShare) {
            this.label = label;
            this.bigFirst = bigFirst;
            this.backlogAwareShare = backlogAwareShare;
        }

        public static final Options LITERAL     = new Options("literal",     false, false);
        public static final Options BIGFIRST    = new Options("bigfirst",    true,  false);
        public static final Options LIT_BACKLOG = new Options("lit_backlog", false, true);
    }

    public static class Plan {
        public final int[] vmOf;
        public final double[] vmCt;

        public int sharePhaseJobs;
        public int eftPhaseJobs;

        public final List<Integer> eftIdx = new ArrayList<>();

        Plan(int jobs, int vms) {
            vmOf = new int[jobs];
            vmCt = new double[vms];
        }
    }

    public static Plan planBatch(double[] jobMi, double[] vmMips, double[] startCt) {
        return planBatch(jobMi, vmMips, startCt, BIG_FIRST);
    }

    public static Plan planBatch(double[] jobMi, double[] vmMips, double[] startCt, boolean bigFirst) {
        return planBatch(jobMi, vmMips, startCt, bigFirst ? Options.BIGFIRST : Options.LITERAL);
    }

    /**
     * Plans a batch of cloudlets across heterogeneous VMs.
     *
     * jobMi   = cloudlet lengths in MI
     * vmMips  = VM processing capacity in MIPS
     * startCt = work already queued on each VM, in seconds
     * o       = variant switches
     */
    public static Plan planBatch(double[] jobMi, double[] vmMips, double[] startCt, Options o) {

        validateInputs(jobMi, vmMips, startCt);

        int n = jobMi.length;
        int m = vmMips.length;

        Plan plan = new Plan(n, m);

        boolean[] done = new boolean[n];
        int remaining = n;

        double totalMips = 0;
        double totalLength = 0;

        for (double mips : vmMips) {
            totalMips += mips;
        }

        for (double mi : jobMi) {
            totalLength += mi;
        }

        // Queued work in MI (backlog seconds x MIPS), used only by the backlog-aware share.
        double queuedMi = 0;
        if (o.backlogAwareShare) {
            for (int j = 0; j < m; j++) {
                queuedMi += startCt[j] * vmMips[j];
            }
        }

        // Initialize VM completion times and calculate VM shares.
        double[] share = new double[m];

        for (int j = 0; j < m; j++) {
            share[j] = (totalLength + queuedMi) * (vmMips[j] / totalMips);
            if (o.backlogAwareShare) {
                share[j] -= startCt[j] * vmMips[j];
            }
            plan.vmCt[j] = startCt[j];
        }

        // Experimental BIG_FIRST variant (not part of the paper's algorithm).
        if (o.bigFirst) {

            while (true) {

                double maxShare = 0;

                for (int j = 0; j < m; j++) {
                    maxShare = Math.max(maxShare, share[j]);
                }

                int big = -1;

                for (int i = 0; i < n; i++) {

                    if (!done[i]
                            && jobMi[i] > maxShare
                            && (big == -1 || jobMi[i] > jobMi[big])) {

                        big = i;
                    }
                }

                if (big == -1) {
                    break;
                }

                int bestVm = findEarliestFinishVm(jobMi[big], vmMips, plan.vmCt);

                plan.vmOf[big] = bestVm;

                plan.vmCt[bestVm] += jobMi[big] / vmMips[bestVm];

                share[bestVm] = Math.max(0, share[bestVm] - jobMi[big]);

                plan.eftPhaseJobs++;
                plan.eftIdx.add(big);

                done[big] = true;
                remaining--;
            }
        }

        // Main DE-RALBA scheduling loop.
        while (remaining > 0) {

            // Find VM with the largest remaining share.
            int maxShareVm = 0;

            for (int j = 1; j < m; j++) {

                if (share[j] > share[maxShareVm]) {
                    maxShareVm = j;
                }
            }

            // Find the largest remaining cloudlet that fits within the selected VM share.
            int selectedJob = -1;

            for (int i = 0; i < n; i++) {

                if (!done[i]
                        && jobMi[i] <= share[maxShareVm]
                        && (selectedJob == -1 || jobMi[i] > jobMi[selectedJob])) {

                    selectedJob = i;
                }
            }

            if (selectedJob != -1) {

                // Share-based allocation.
                plan.vmOf[selectedJob] = maxShareVm;

                share[maxShareVm] -= jobMi[selectedJob];

                plan.vmCt[maxShareVm] += jobMi[selectedJob] / vmMips[maxShareVm];

                plan.sharePhaseJobs++;

            } else {

                // EFT fallback: take the largest remaining cloudlet.
                int largestJob = -1;

                for (int i = 0; i < n; i++) {

                    if (!done[i]
                            && (largestJob == -1 || jobMi[i] > jobMi[largestJob])) {

                        largestJob = i;
                    }
                }

                // Assign it to the VM with the earliest finish time.
                int bestVm = findEarliestFinishVm(jobMi[largestJob], vmMips, plan.vmCt);

                plan.vmOf[largestJob] = bestVm;

                plan.vmCt[bestVm] += jobMi[largestJob] / vmMips[bestVm];

                plan.eftPhaseJobs++;
                plan.eftIdx.add(largestJob);

                selectedJob = largestJob;
            }

            done[selectedJob] = true;
            remaining--;
        }

        return plan;
    }

    /** Returns the VM with the minimum completion time: existing VM completion time + length / MIPS. */
    private static int findEarliestFinishVm(double jobLength, double[] vmMips, double[] vmCt) {

        int bestVm = 0;
        double bestCompletionTime = Double.MAX_VALUE;

        for (int j = 0; j < vmMips.length; j++) {

            double completionTime = vmCt[j] + jobLength / vmMips[j];

            if (completionTime < bestCompletionTime) {

                bestCompletionTime = completionTime;
                bestVm = j;
            }
        }

        return bestVm;
    }

    /** Checks input arrays and values before scheduling. */
    private static void validateInputs(double[] jobMi, double[] vmMips, double[] startCt) {

        if (jobMi == null || vmMips == null || startCt == null) {
            throw new IllegalArgumentException("Input arrays cannot be null.");
        }

        if (vmMips.length == 0) {
            throw new IllegalArgumentException("At least one VM is required.");
        }

        if (startCt.length != vmMips.length) {
            throw new IllegalArgumentException("startCt and vmMips must have equal lengths.");
        }

        for (double mips : vmMips) {

            if (!Double.isFinite(mips) || mips <= 0) {
                throw new IllegalArgumentException("VM MIPS must be positive and finite.");
            }
        }

        for (double mi : jobMi) {

            if (!Double.isFinite(mi) || mi < 0) {
                throw new IllegalArgumentException("Job MI must be non-negative and finite.");
            }
        }

        for (double ct : startCt) {

            if (!Double.isFinite(ct) || ct < 0) {
                throw new IllegalArgumentException("Start times must be non-negative and finite.");
            }
        }
    }
}