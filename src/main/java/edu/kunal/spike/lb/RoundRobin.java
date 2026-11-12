
package edu.kunal.spike.lb;

import java.util.List;

public class RoundRobin implements Scheduler {
    private int next = 0;

    public String name() { return "RR"; }

    public void map(List<Task> batch, List<VmState> vms, double now) {
        for (Task t : batch) {
            vms.get(next).assign(t, now);
            next = (next + 1) % vms.size();
        }
    }
}