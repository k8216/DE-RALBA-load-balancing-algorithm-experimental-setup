
package edu.kunal.spike.lb;


import java.util.List;

public interface Scheduler {
    String name();

    /** Assign every task in batch to a VM by calling vm.assign(task, now). */
    void map(List<Task> batch, List<VmState> vms, double now);
}