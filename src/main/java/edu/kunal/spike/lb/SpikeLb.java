package edu.kunal.spike.lb;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.utilizationmodels.UtilizationModelFull;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class SpikeLb {

    // SL-4: Parses the GoCJ dataset file using WorkloadGen.loadPool for consistent header skipping
    private static List<Cloudlet> loadGoCJ(String fileName) {
        List<Cloudlet> cloudlets = new ArrayList<>();
        long idCounter = 0;

        try {
            double[] pool = WorkloadGen.loadPool(Paths.get(fileName));
            for (double len : pool) {
                Cloudlet c = new CloudletSimple((long) len, 1);
                c.setId(idCounter++);
                c.setUtilizationModelCpu(new UtilizationModelFull());
                c.setUtilizationModelRam(new UtilizationModelDynamic(0.1));
                c.setUtilizationModelBw(new UtilizationModelDynamic(0.1));
                cloudlets.add(c);
            }
        } catch (IOException e) {
            System.err.println("Failed to load GoCJ file: " + e.getMessage());
            System.exit(1);
        }
        return cloudlets;
    }

    private static List<Cloudlet> identicalJobs(int n) {
        List<Cloudlet> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Cloudlet c = new CloudletSimple(10_000, 1);
            c.setId(i);
            c.setUtilizationModelCpu(new UtilizationModelFull());
            c.setUtilizationModelRam(new UtilizationModelDynamic(0.1));
            c.setUtilizationModelBw(new UtilizationModelDynamic(0.1));
            list.add(c);
        }
        return list;
    }

    private static final int HOSTS = 30;
    private static final int HOST_PES = 4;
    private static final long HOST_MIPS = 4000;

    private static final int[][] VM_GROUPS = {
        {100, 7}, {500, 7}, {550, 6}, {1000, 6}, {1250, 6}, {1500, 6}, {1750, 6}, {2000, 6}
    };

    // ---- experiment switches ----
    private static final String ALGO = "DERALBA";        // "RR", "EFT" or "DERALBA"
    private static final String WORKLOAD = "GOCJ";       // "IDENTICAL" or "GOCJ"
    private static final boolean BATCH = false;          // true = all jobs arrive at time 0
    private static final boolean SPIKE = false;          // ignored when BATCH is true
    private static final double ROUND_LEN = 1.0;         // seconds, DE-RALBA only
    private static final boolean REBALANCE = false;

    // ---- arrival pattern (used when BATCH is false) ----
    private static final double STEADY_GAP = WORKLOAD.equals("GOCJ") ? 3.0 : 0.3;
    private static final double SPIKE_GAP = WORKLOAD.equals("GOCJ") ? 0.2 : 0.02;
    private static final int SPIKE_START = 30;
    private static final int SPIKE_END = 80;

    // ---- DE-RALBA diagnostics ----
    private static int shareJobs = 0;
    private static int eftJobs = 0;

    private static double[] applyArrivals(List<Cloudlet> jobs, boolean spike) {
        double[] arrival = new double[jobs.size()];
        double t = 0;
        for (int i = 0; i < jobs.size(); i++) {
            jobs.get(i).setSubmissionDelay(t);
            arrival[i] = t;
            if (!BATCH) {
                t += (spike && i >= SPIKE_START && i < SPIKE_END) ? SPIKE_GAP : STEADY_GAP;
            }
        }
        return arrival;
    }

    private static Function<Cloudlet, Vm> eftMapper(List<Vm> vms) {
        double[] ready = new double[vms.size()];
        Map<Cloudlet, Vm> alreadyMapped = new HashMap<>();
        return c -> {
            Vm previous = alreadyMapped.get(c);
            if (previous != null) {
                return previous;
            }
            double arrival = c.getSubmissionDelay();
            int best = 0;
            double bestFinish = Double.MAX_VALUE;
            for (int i = 0; i < vms.size(); i++) {
                double finish = Math.max(ready[i], arrival) + c.getLength() / vms.get(i).getMips();
                if (finish < bestFinish) {
                    bestFinish = finish;
                    best = i;
                }
            }
            ready[best] = bestFinish;
            Vm chosen = vms.get(best);
            alreadyMapped.put(c, chosen);
            return chosen;
        };
    }

    private static Map<Long, Vm> deRalbaRounds(List<Cloudlet> jobs, double[] arrival, List<Vm> vms) {
        Map<Long, Vm> mapping = new HashMap<>();
        int m = vms.size();
        double[] mips = new double[m];
        for (int j = 0; j < m; j++) {
            mips[j] = vms.get(j).getMips();
        }
        double[] ready = new double[m];

        int i = 0;
        while (i < jobs.size()) {
            long round = (long) Math.floor(arrival[i] / ROUND_LEN);
            double decisionTime = (round + 1) * ROUND_LEN;
            int end = i;
            while (end < jobs.size() && (long) Math.floor(arrival[end] / ROUND_LEN) == round) {
                end++;
            }
            int n = end - i;
            double[] mi = new double[n];
            for (int k = 0; k < n; k++) {
                mi[k] = jobs.get(i + k).getLength();
            }
            double[] backlog = new double[m];
            for (int j = 0; j < m; j++) {
                backlog[j] = Math.max(0, ready[j] - decisionTime);
            }
            DeRalba.Plan plan = DeRalba.planBatch(mi, mips, backlog);
            shareJobs += plan.sharePhaseJobs;
            eftJobs += plan.eftPhaseJobs;
            for (int idx : plan.eftIdx) {
                System.out.println(String.format("EFT-phase job id %d: %.0f MI -> VM %d (%.0f MIPS)",
                        jobs.get(i + idx).getId(), mi[idx], plan.vmOf[idx], mips[plan.vmOf[idx]]));
            }
            for (int k = 0; k < n; k++) {
                Cloudlet c = jobs.get(i + k);
                mapping.put(c.getId(), vms.get(plan.vmOf[k]));
                c.setSubmissionDelay(decisionTime);
            }
            for (int j = 0; j < m; j++) {
                ready[j] = decisionTime + plan.vmCt[j];
            }
            i = end;
        }
        return mapping;
    }

    public static void main(String[] args) {
        // SL-2: Reset static diagnostics per run
        shareJobs = 0;
        eftJobs = 0;

        CloudSimPlus simulation = new CloudSimPlus();

        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < HOSTS; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < HOST_PES; p++) {
                peList.add(new PeSimple(HOST_MIPS));
            }
            hostList.add(new HostSimple(16_384, 10_000, 1_000_000, peList));
        }
        Datacenter datacenter = new DatacenterSimple(simulation, hostList);

        DatacenterBroker broker = new DatacenterBrokerSimple(simulation);

        List<Vm> vmList = new ArrayList<>();
        for (int[] group : VM_GROUPS) {
            for (int k = 0; k < group[1]; k++) {
                Vm vm = new VmSimple(group[0], 1);
                vm.setRam(512).setBw(1000).setSize(10_000);
                vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
                vmList.add(vm);
            }
        }

        List<Cloudlet> cloudletList = WORKLOAD.equals("GOCJ") ? loadGoCJ("GoCJ_100.txt") : identicalJobs(100);
        double[] arrival = applyArrivals(cloudletList, SPIKE);

        double totalMi = cloudletList.stream().mapToDouble(Cloudlet::getLength).sum();
        double minMi = cloudletList.stream().mapToDouble(Cloudlet::getLength).min().orElse(0);
        double maxMi = cloudletList.stream().mapToDouble(Cloudlet::getLength).max().orElse(0);
        double totalMips = vmList.stream().mapToDouble(Vm::getMips).sum();
        System.out.println(String.format(
                "WORKLOAD %s: %d jobs, MI min/avg/max = %.0f / %.0f / %.0f, total MI = %.0f, ideal makespan = %.1f s",
                WORKLOAD, cloudletList.size(), minMi, totalMi / cloudletList.size(), maxMi,
                totalMi, totalMi / totalMips));

        // SL-1: Explicit Round Robin mapper alongside EFT and DERALBA
        if (ALGO.equals("RR")) {
            broker.setVmMapper(c -> vmList.get((int) (c.getId() % vmList.size())));
        } else if (ALGO.equals("EFT")) {
            broker.setVmMapper(eftMapper(vmList));
        } else if (ALGO.equals("DERALBA")) {
            Map<Long, Vm> plan = deRalbaRounds(cloudletList, arrival, vmList);
            broker.setVmMapper(c -> plan.get(c.getId()));
        }

        // SL-3: Fixed IDLE-VM REBALANCER (Algorithm 1, lines 33-45)
        // Restricted to DE-RALBA variants, tracks targeted VMs, uses remaining length, and loops through idle VMs
        if (REBALANCE && ALGO.startsWith("DERALBA")) {
            for (Cloudlet cld : cloudletList) {
                cld.addOnFinishListener(info -> {
                    Set<Vm> targeted = new HashSet<>();
                    for (Vm idleVm : vmList) {
                        if (targeted.contains(idleVm)) {
                            continue;
                        }
                        if (idleVm.getCloudletScheduler().getCloudletWaitingList().isEmpty() &&
                            idleVm.getCloudletScheduler().getCloudletExecList().isEmpty()) {

                            Vm overloadedVm = null;
                            int maxWaiting = 0;
                            for (Vm v : vmList) {
                                int waiting = v.getCloudletScheduler().getCloudletWaitingList().size();
                                if (waiting > maxWaiting) {
                                    maxWaiting = waiting;
                                    overloadedVm = v;
                                }
                            }

                            if (overloadedVm != null) {
                                Cloudlet minCld = null;
                                double minLen = Double.MAX_VALUE;
                                for (CloudletExecution cle : overloadedVm.getCloudletScheduler().getCloudletWaitingList()) {
                                    Cloudlet cl = cle.getCloudlet();
                                    if (cl.getLength() < minLen) {
                                        minLen = cl.getLength();
                                        minCld = cl;
                                    }
                                }

                                if (minCld != null) {
                                    double idleMips = idleVm.getMips();
                                    double currentMips = overloadedVm.getMips();
                                    double timeOnIdle = minCld.getLength() / idleMips;

                                    double timeOnOverloaded = 0;
                                    // Use remaining length for executing tasks, not full length
                                    for (CloudletExecution cle : overloadedVm.getCloudletScheduler().getCloudletExecList()) {
                                        timeOnOverloaded += cle.getRemainingCloudletLength();
                                    }
                                    for (CloudletExecution cle : overloadedVm.getCloudletScheduler().getCloudletWaitingList()) {
                                        timeOnOverloaded += cle.getCloudlet().getLength();
                                        if (cle.getCloudlet() == minCld) {
                                            break;
                                        }
                                    }
                                    timeOnOverloaded /= currentMips;

                                    if (timeOnIdle < timeOnOverloaded) {
                                        overloadedVm.getCloudletScheduler().cloudletCancel(minCld);
                                        minCld.reset();
                                        minCld.setSubmissionDelay(0);
                                        broker.bindCloudletToVm(minCld, idleVm);
                                        broker.submitCloudletList(Collections.singletonList(minCld));
                                        targeted.add(idleVm);
                                        // no break: continue to check next idle VM
                                    }
                                }
                            }
                        }
                    }
                });
            }
        }

        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudletList);

        simulation.start();

        // M-4: Deduplicate by the LAST record per cloudlet ID (migrated cloudlets appear twice)
        Map<Long, Cloudlet> byId = new java.util.LinkedHashMap<>();
        for (Cloudlet c : broker.getCloudletFinishedList()) {
            byId.put(c.getId(), c);
        }
        List<Cloudlet> uniqueFinished = new ArrayList<>(byId.values());
        System.out.println("Raw finished records: " + broker.getCloudletFinishedList().size()
                + ", unique: " + uniqueFinished.size());

        String label = ALGO + (ALGO.equals("DERALBA") ? " T=" + ROUND_LEN : "")
                + (BATCH ? ", BATCH" : (SPIKE ? ", SPIKE" : ", steady")) + ", " + WORKLOAD
                + (REBALANCE ? ", rebalance" : "");

        Metrics.report(label, uniqueFinished, vmList, cloudletList.size(), arrival);
        Diagnostics.perVm(uniqueFinished, vmList);
        Diagnostics.latestJobs(uniqueFinished, 5, arrival);

        if (ALGO.equals("DERALBA")) {
            System.out.println("DE-RALBA share-phase jobs: " + shareJobs + ", EFT-phase jobs: " + eftJobs);
        }
    }
}
