
package edu.kunal.spike.lb;


import java.util.*;

public class DeRalba2 implements Scheduler {
    public String name() { return "DE-RALBA"; }

    public void map(List<Task> batch, List<VmState> vms, double now) {
        double totalMips = 0, backlog = 0, pending = 0;
        for (VmState v : vms) { totalMips += v.mips; backlog += v.backlogMI(now); }
        for (Task t : batch) pending += t.length();
        double totalMI = pending + backlog;

        double[] share = new double[vms.size()];
        for (int j = 0; j < vms.size(); j++)
            share[j] = vms.get(j).mips / totalMips * totalMI - vms.get(j).backlogMI(now);

        TreeMap<Long, ArrayDeque<Task>> pool = new TreeMap<>();
        for (Task t : batch) pool.computeIfAbsent(t.length(), k -> new ArrayDeque<>()).add(t);

        while (!pool.isEmpty()) {
            int j = 0;
            for (int i = 1; i < share.length; i++) if (share[i] > share[j]) j = i;

            Map.Entry<Long, ArrayDeque<Task>> e = pool.floorEntry((long) Math.floor(share[j]));
            Task t;
            if (e != null) {
                t = e.getValue().poll();                       // largest task that fits the share
            } else {
                e = pool.lastEntry();                          // fallback: largest task, min finish time among share > 0
                t = e.getValue().poll();
                int best = j;
                for (int k = 0; k < vms.size(); k++)
                    if (share[k] > 0 && vms.get(k).finishTimeOf(t, now) < vms.get(best).finishTimeOf(t, now)) best = k;
                j = best;
            }
            if (e.getValue().isEmpty()) pool.remove(e.getKey());

            vms.get(j).assign(t, now);
            share[j] -= t.length();
        }
    }
}