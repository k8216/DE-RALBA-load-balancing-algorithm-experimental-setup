/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package edu.kunal.spike.lb;



import java.util.*;

public class Mapper {
    public static class Out { public double peakBacklogMI; public double decisionMs; }

    /** Every roundLen seconds, give the tasks that arrived since the last round to the scheduler. */
    public static Out run(Scheduler s, List<Task> tasks, List<VmState> vms, double roundLen) {
        Out out = new Out();
        int idx = 0; double now = roundLen; long ns = 0;
        while (idx < tasks.size()) {
            List<Task> batch = new ArrayList<>();
            while (idx < tasks.size() && tasks.get(idx).arrival() <= now) batch.add(tasks.get(idx++));
            if (!batch.isEmpty()) {
                double bl = 0;
                for (VmState v : vms) bl += v.backlogMI(now);
                out.peakBacklogMI = Math.max(out.peakBacklogMI, bl);
                long t0 = System.nanoTime();
                s.map(batch, vms, now);
                ns += System.nanoTime() - t0;
            }
            now += roundLen;
        }
        out.decisionMs = ns / 1e6;
        return out;
    }
}