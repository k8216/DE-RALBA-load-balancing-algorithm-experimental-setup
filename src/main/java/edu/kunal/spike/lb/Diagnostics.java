package edu.kunal.spike.lb;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.vms.Vm;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Diagnostics {

    public static void perVm(List<Cloudlet> finished, List<Vm> vms) {
        perVmSummary(finished);
    }

    public static void perVmSummary(List<Cloudlet> finished) {
        System.out.println("----- PER-VM SUMMARY -----");
        System.out.println("vm |  MIPS | jobs | total exec (s) | last finish (s)");

        Map<Vm, List<Cloudlet>> byVm = finished.stream()
                .collect(Collectors.groupingBy(Cloudlet::getVm));

        byVm.entrySet().stream()
                .sorted(Comparator.comparingDouble(e -> e.getKey().getMips()))
                .forEach(e -> {
                    Vm vm = e.getKey();
                    List<Cloudlet> list = e.getValue();
                    double totalExec = list.stream()
                            .mapToDouble(c -> c.getFinishTime() - c.getStartTime())
                            .sum();
                    double lastFinish = list.stream()
                            .mapToDouble(Cloudlet::getFinishTime)
                            .max().orElse(0);
                    System.out.println(String.format("%2d | %5.0f | %4d | %14.2f | %15.2f",
                            vm.getId(), (double) vm.getMips(), list.size(), totalExec, lastFinish));
                });
    }

    public static void latestJobs(List<Cloudlet> finished, int n, double[] arrival) {
        System.out.println();
        System.out.println("----- LAST " + n + " JOBS TO FINISH -----");
        System.out.println("job | vm | vm MIPS | planned arrival | dispatch | start | finish");
        finished.stream()
                .sorted(Comparator.comparingDouble(Cloudlet::getFinishTime).reversed())
                .limit(n)
                .forEach(c -> {
                    double arr = (arrival != null && c.getId() >= 0 && c.getId() < arrival.length)
                            ? arrival[(int) c.getId()]
                            : Double.NaN;
                    System.out.println(String.format("%3d | %2d | %7.0f | arrival %8.2f | dispatch %8.2f | start %8.2f | finish %8.2f",
                            c.getId(), c.getVm().getId(), (double) c.getVm().getMips(),
                            arr, c.getSubmissionDelay(), c.getStartTime(), c.getFinishTime()));
                });
    }

    public static void latestJobs(List<Cloudlet> finished, int n) {
        latestJobs(finished, n, null);
    }
}
