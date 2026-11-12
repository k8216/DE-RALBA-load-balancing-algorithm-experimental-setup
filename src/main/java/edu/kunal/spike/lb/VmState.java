
package edu.kunal.spike.lb;


import java.util.*;

public class VmState {
    public record Assigned(Task task, double submitTime) {}

    public final int id;
    public final double mips;
    public double readyTime = 0;
    public final List<Assigned> assigned = new ArrayList<>();

    public VmState(int id, double mips) { this.id = id; this.mips = mips; }

    public double backlogMI(double now) { return Math.max(0, readyTime - now) * mips; }

    public double finishTimeOf(Task t, double now) {
        return Math.max(readyTime, now) + t.length() / mips;
    }

    public void assign(Task t, double now) {
        readyTime = finishTimeOf(t, now);
        assigned.add(new Assigned(t, now));
    }
}