package edu.kunal.spike.lb;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns "job i goes to VM v and reaches it at time d" into start and finish times.
 * Each VM runs one job at a time, first come first served by dispatch time (ties by job index).
 * This is the same execution model that DeRalbaDynamic uses internally, and it matches a
 * space-shared, single-PE VM in CloudSim Plus.
 */
public final class QueueExec {

    private QueueExec() {
    }

    public static List<SpikeMetrics.JobRecord> run(double[] arrival, double[] mi, int[] vm, double[] dispatch,
                                                   double[] vmMips) {
        int n = mi.length;
        int m = vmMips.length;
        List<List<Integer>> perVm = new ArrayList<>();
        for (int j = 0; j < m; j++) {
            perVm.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            // QE-2: Safety assertion verifying dispatch is never before arrival
            if (dispatch[i] < arrival[i] - 1e-9) {
                throw new IllegalArgumentException("Job " + i + " dispatched before it arrived: arrival="
                        + arrival[i] + " dispatch=" + dispatch[i]);
            }
            if (vm[i] < 0 || vm[i] >= m) {
                throw new IllegalArgumentException("Job " + i + " has VM index " + vm[i]);
            }
            perVm.get(vm[i]).add(i);
        }
        SpikeMetrics.JobRecord[] out = new SpikeMetrics.JobRecord[n];
        for (int j = 0; j < m; j++) {
            List<Integer> q = perVm.get(j);
            q.sort((a, b) -> {
                int c = Double.compare(dispatch[a], dispatch[b]);
                return c != 0 ? c : Integer.compare(a, b);
            });
            double cursor = 0.0;
            for (int i : q) {
                double start = Math.max(cursor, dispatch[i]);
                double finish = start + mi[i] / vmMips[j];
                out[i] = new SpikeMetrics.JobRecord(i, j, mi[i], arrival[i], dispatch[i], start, finish);
                cursor = finish;
            }
        }
        List<SpikeMetrics.JobRecord> list = new ArrayList<>(n);
        for (SpikeMetrics.JobRecord r : out) {
            list.add(r);
        }
        return list;
    }
}
