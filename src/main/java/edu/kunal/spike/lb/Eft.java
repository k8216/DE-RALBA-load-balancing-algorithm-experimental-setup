
package edu.kunal.spike.lb;



import java.util.List;

public class Eft implements Scheduler {
    public String name() { return "EFT"; }

    public void map(List<Task> batch, List<VmState> vms, double now) {
        for (Task t : batch) {
            VmState best = vms.get(0);
            for (VmState v : vms)
                if (v.finishTimeOf(t, now) < best.finishTimeOf(t, now)) best = v;
            best.assign(t, now);
        }
    }
}