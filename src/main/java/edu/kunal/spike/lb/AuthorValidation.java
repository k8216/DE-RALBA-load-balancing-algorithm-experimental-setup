package edu.kunal.spike.lb;

import java.nio.file.Files;
import java.nio.file.Paths;

public final class AuthorValidation {
    // the 15 VMs created by the author's VMCreator.createVms(1, 15)
    static final double[] VM_MIPS = {300, 500, 550, 1000, 800, 650, 850, 500, 300, 500, 550, 1000, 800, 650, 850};

    public static void main(String[] args) throws Exception {
        double[] pool = WorkloadGen.loadPool(Paths.get("GoCJ_100.txt"));
        double totalMips = 0;
        for (double v : VM_MIPS) {
            totalMips += v;
        }
        WorkloadGen.Workload wl = WorkloadGen.generate(
                WorkloadGen.Config.spike("validation", 100, totalMips, 0.5, 20, 10, 5), pool, 1);

        int n = wl.mi.length;
        double[] arrival = new double[n];
        StringBuilder jobs = new StringBuilder();
        for (int i = 0; i < n; i++) {
            arrival[i] = (i == 0) ? 0 : Math.ceil(wl.arrival[i]);
            jobs.append((long) wl.mi[i]).append(',').append((int) arrival[i]).append('\n');
        }
        Files.writeString(Paths.get("validation_jobs.csv"), jobs.toString());

        DeRalbaDynamic.Result r = DeRalbaAuthor.run(arrival, wl.mi, VM_MIPS);
        StringBuilder out = new StringBuilder("job,vm\n");
        for (int i = 0; i < n; i++) {
            out.append(i).append(',').append(r.finalVm[i]).append('\n');
        }
        Files.writeString(Paths.get("model_assign.csv"), out.toString());
        System.out.println("model makespan: " + r.predictedMakespan + ", migrations: " + r.migrations);
    }
}