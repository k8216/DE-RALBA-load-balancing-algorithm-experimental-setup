
package edu.kunal.spike.lb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.vms.Vm;

public class DeRalbaUpdateScheduler {

    private static class VmStatus {
        Vm vm;
        double totalAssignedMI;
        double executedMI;
        double remainingMI;
        double readyTime;
        double lastUpdateTime;

        VmStatus(Vm vm) {
            this.vm = vm;
        }
    }

    private final List<Cloudlet> cloudlets;
    private final Map<Long, Vm> mapping;
    private final List<VmStatus> statusList = new ArrayList<>();

    public DeRalbaUpdateScheduler(
            List<Cloudlet> cloudlets,
            Map<Long, Vm> mapping,
            List<Vm> vms) {

        this.cloudlets = cloudlets;
        this.mapping = mapping;

        // Store each VM separately using its object reference.
        // This avoids duplicate-ID problems before broker submission.
        for (Vm vm : vms) {
            statusList.add(new VmStatus(vm));
        }

        // Calculate total assigned MI for each VM.
        for (Cloudlet cloudlet : cloudlets) {

            Vm assignedVm = mapping.get(cloudlet.getId());

            if (assignedVm == null) {
                continue;
            }

            for (VmStatus status : statusList) {

                if (assignedVm == status.vm) {
                    status.totalAssignedMI += cloudlet.getLength();
                    break;
                }
            }
        }

        System.out.println(
                "Update-Scheduler initialized with "
                        + statusList.size()
                        + " VMs."
        );
    }

    public void update(double currentTime) {

        for (VmStatus status : statusList) {

            double executed = 0;
            double remaining = 0;

            for (Cloudlet cloudlet : cloudlets) {

                Vm assignedVm = mapping.get(cloudlet.getId());

                if (assignedVm != status.vm) {
                    continue;
                }

                double length = cloudlet.getLength();

                if (cloudlet.isFinished()) {

                    executed += length;

                } else {

                    double finished =
                            cloudlet.getFinishedLengthSoFar();

                    finished = Math.max(
                            0,
                            Math.min(length, finished)
                    );

                    executed += finished;
                    remaining += length - finished;
                }
            }

            status.executedMI = executed;
            status.remainingMI = remaining;
            status.lastUpdateTime = currentTime;

            double mips = status.vm.getMips();

            status.readyTime = mips > 0
                    ? currentTime + remaining / mips
                    : currentTime;
        }
    }

    public void printFinalStatus() {

        System.out.println();
        System.out.println("===== FINAL VM STATUS TABLE =====");

        System.out.printf(
                "%-5s | %-6s | %-12s | %-12s | %-12s | %-10s%n",
                "VM",
                "MIPS",
                "Total MI",
                "Executed MI",
                "Remaining MI",
                "Ready Time"
        );

        double grandTotal = 0;
        double grandExecuted = 0;
        double grandRemaining = 0;

        for (VmStatus status : statusList) {

            double accounted =
                    status.executedMI + status.remainingMI;

            System.out.printf(
                    "%-5d | %-6.0f | %-12.0f | %-12.0f | %-12.0f | %-10.2f%n",
                    status.vm.getId(),
                    status.vm.getMips(),
                    status.totalAssignedMI,
                    status.executedMI,
                    status.remainingMI,
                    status.readyTime
            );

            if (Math.abs(
                    status.totalAssignedMI - accounted
            ) > 0.01) {

                System.out.printf(
                        "WARNING: VM %d mismatch: %.2f MI%n",
                        status.vm.getId(),
                        status.totalAssignedMI - accounted
                );
            }

            grandTotal += status.totalAssignedMI;
            grandExecuted += status.executedMI;
            grandRemaining += status.remainingMI;
        }

        System.out.println(
                "------------------------------------------------------------"
        );

        System.out.printf(
                "TOTAL | Assigned: %.0f MI | Executed: %.0f MI | Remaining: %.0f MI%n",
                grandTotal,
                grandExecuted,
                grandRemaining
        );

        System.out.printf(
                "Global accounting difference: %.2f MI%n",
                grandTotal - grandExecuted - grandRemaining
        );

        System.out.println(
                "VM status entries: " + statusList.size()
        );
    }
}
