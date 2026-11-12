/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package edu.kunal.spike.lb;



import java.util.*;

public class DynamicMaxMin implements Scheduler {
    public String name() { return "DynMaxMin"; }

    public void map(List<Task> batch, List<VmState> vms, double now) {
        List<Task> rest = new ArrayList<>(batch);
        while (!rest.isEmpty()) {
            Task pick = null; VmState pickVm = null; double worst = -1;
            for (Task t : rest) {
                VmState best = vms.get(0);
                for (VmState v : vms)
                    if (v.finishTimeOf(t, now) < best.finishTimeOf(t, now)) best = v;
                double f = best.finishTimeOf(t, now);
                if (f > worst) { worst = f; pick = t; pickVm = best; }   // max of the per-task minimum finish times
            }
            pickVm.assign(pick, now);
            rest.remove(pick);
        }
    }
}