package edu.kunal.spike.lb;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.vms.Vm;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class Metrics {

    public static void report(
            String label,
            List<Cloudlet> finished,
            List<Vm> vms,
            int submitted,
            double[] arrival) {

        // IdentityHashMap avoids accidental collisions between VM IDs.
        Map<Vm, Double> lastFinish = new IdentityHashMap<>();
        Map<Vm, Double> busyTime = new IdentityHashMap<>();

        for (Cloudlet c : finished) {
            Vm vm = c.getVm();
            if (vm == null) {
                continue;
            }
            lastFinish.merge(vm, c.getFinishTime(), Math::max);
            busyTime.merge(vm, c.getLength() / vm.getMips(), Double::sum);
        }

        double maxF = 0, minF = Double.MAX_VALUE, sumF = 0;
        double maxB = 0, minB = Double.MAX_VALUE, sumB = 0;

        for (Vm vm : vms) {
            double fin = lastFinish.getOrDefault(vm, 0.0);
            double busy = busyTime.getOrDefault(vm, 0.0);
            maxF = Math.max(maxF, fin);
            minF = Math.min(minF, fin);
            sumF += fin;
            maxB = Math.max(maxB, busy);
            minB = Math.min(minB, busy);
            sumB += busy;
        }

        int m = vms.size();
        double makespanSim = maxF;       // when the last job finished in the simulator
        double makespanPaper = maxB;     // Eq. 12, with VM_CT from Eq. 10 (busy time)
        double arurPaper = (m > 0 && maxB > 0) ? (sumB / m) / maxB : 0;                // Eq. 13
        double arurFinish = (m > 0 && makespanSim > 0) ? sumF / (m * makespanSim) : 0; // finish-time version
        double arurBusy = (m > 0 && makespanSim > 0) ? sumB / (m * makespanSim) : 0;   // busy / simulator makespan

        double imbalanceFinish = (m > 0 && sumF > 0) ? (maxF - minF) / (sumF / m) : 0;
        double imbalanceBusy = (m > 0 && sumB > 0) ? (maxB - minB) / (sumB / m) : 0;
        double throughput = makespanSim > 0 ? (double) finished.size() / makespanSim : 0;

        double responseSum = 0;
        double maxResponse = 0;
        int responseCount = 0;
        if (arrival != null) {
            for (Cloudlet c : finished) {
                long id = c.getId();
                if (id >= 0 && id < arrival.length) {
                    double response = c.getFinishTime() - arrival[(int) id];
                    responseSum += response;
                    maxResponse = Math.max(maxResponse, response);
                    responseCount++;
                }
            }
        }
        double avgResponse = responseCount > 0 ? responseSum / responseCount : 0;

        System.out.println();
        System.out.println("===== RESULTS: " + label + " =====");
        System.out.println("Finished                  : " + finished.size() + " of " + submitted);
        System.out.printf("Makespan                  : %.2f s%n", makespanSim);
        // M-1: Clarify that this is busy / sim makespan, distinct from paper ARUR
        System.out.printf("ARUR (busy / sim makespan): %.3f%n", arurBusy);
        System.out.printf("Makespan (paper)          : %.2f s%n", makespanPaper);
        System.out.printf("ARUR (paper)              : %.3f%n", arurPaper);
        System.out.printf("ARUR (finish-based)       : %.3f%n", arurFinish);
        System.out.printf("Throughput                : %.3f jobs/s%n", throughput);
        // M-2: Rename to Range / mean to distinguish from time-averaged imbalance
        System.out.printf("Range / mean (finish)     : %.3f%n", imbalanceFinish);
        System.out.printf("Range / mean (busy)       : %.3f%n", imbalanceBusy);
        System.out.printf("Avg response              : %.2f s%n", avgResponse);
        System.out.printf("Max response              : %.2f s%n", maxResponse);
    }
}
