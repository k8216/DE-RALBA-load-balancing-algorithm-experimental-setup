
package edu.kunal.spike.lb;


import java.util.*;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

public class CloudSimRunner {

    public static Result run(Scheduler s, List<Task> tasks, double[] mips, double roundLen) {
        // 1) scheduling decisions on the queue model
        List<VmState> model = new ArrayList<>();
        for (int i = 0; i < mips.length; i++) model.add(new VmState(i, mips[i]));
        Mapper.Out mo = Mapper.run(s, tasks, model, roundLen);

        // 2) CloudSim Plus: one host + one single-PE FIFO VM per machine
        CloudSimPlus sim = new CloudSimPlus();
        List<Host> hosts = new ArrayList<>();
        List<Vm> vms = new ArrayList<>();
        for (double m : mips) {
            List<Pe> pes = new ArrayList<>();
            pes.add(new PeSimple(m));
            hosts.add(new HostSimple(8192, 100_000, 1_000_000, pes));

            Vm vm = new VmSimple(m, 1);
            vm.setRam(512).setBw(1000).setSize(10_000);
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vms.add(vm);
        }
        new DatacenterSimple(sim, hosts, new VmAllocationPolicySimple());
        DatacenterBroker broker = new DatacenterBrokerSimple(sim);
        broker.submitVmList(vms);

        Map<Cloudlet, Double> arrivalOf = new IdentityHashMap<>();
        List<Cloudlet> cloudlets = new ArrayList<>();
        for (int j = 0; j < model.size(); j++) {
            for (VmState.Assigned a : model.get(j).assigned) {
                Cloudlet c = new CloudletSimple(a.task().id(), a.task().length(), 1);
                c.setUtilizationModel(new UtilizationModelFull());
                c.setSubmissionDelay(a.submitTime());          // submitted when the round decided
                broker.bindCloudletToVm(c, vms.get(j));
                cloudlets.add(c);
                arrivalOf.put(c, a.task().arrival());
            }
        }
        broker.submitCloudletList(cloudlets);
        sim.start();

        // 3) metrics from the finished cloudlets
        List<Cloudlet> finished = broker.getCloudletFinishedList();
        if (finished.size() != tasks.size())
            System.err.println("WARNING: finished " + finished.size() + " of " + tasks.size());

        Map<Vm, Integer> vmIndex = new IdentityHashMap<>();
        for (int i = 0; i < vms.size(); i++) vmIndex.put(vms.get(i), i);

        double[] busy = new double[vms.size()];
        List<Double> resp = new ArrayList<>();
        double makespan = 0;
        for (Cloudlet c : finished) {
            resp.add(c.getFinishTime() - arrivalOf.get(c));
            busy[vmIndex.get(c.getVm())] += c.getTotalExecutionTime();
            makespan = Math.max(makespan, c.getFinishTime());
        }
        Collections.sort(resp);

        double modelMakespan = 0;
        for (VmState v : model) modelMakespan = Math.max(modelMakespan, v.readyTime);
        if (Math.abs(modelMakespan - makespan) > 0.01 * makespan)
            System.err.printf("WARNING: model makespan %.2f vs CloudSim %.2f%n", modelMakespan, makespan);

        double sum = 0, max = 0, min = Double.MAX_VALUE;
        for (double b : busy) { sum += b; max = Math.max(max, b); min = Math.min(min, b); }
        double mean = resp.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        return new Result(mean, pct(resp, 0.95), pct(resp, 0.99), makespan,
                sum / (vms.size() * makespan), (max - min) / (sum / vms.size()),
                mo.peakBacklogMI, mo.decisionMs);
    }

    private static double pct(List<Double> sorted, double p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }
}
