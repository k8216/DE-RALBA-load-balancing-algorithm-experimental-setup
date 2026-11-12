package edu.kunal.spike.lb;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Unit tests for SpikeMetrics (SM-6):
 * 1. Single job on a single VM.
 * 2. Two jobs on two VMs with heterogeneous MIPS (validates SM-1 and SM-2).
 * 3. Recovery parameter preservation in result rows (validates SM-3).
 */
public final class SpikeMetricsTest {

    public static void main(String[] args) {
        System.out.println("======================================================================");
        System.out.println("                  SPIKE METRICS UNIT TEST SUITE                       ");
        System.out.println("======================================================================\n");

        boolean t1 = testSingleJobSingleVm();
        boolean t2 = testTwoJobsHeterogeneousVms();
        boolean t3 = testRecoveryParamsPreserved();

        System.out.println("\n----------------------------------------------------------------------");
        if (t1 && t2 && t3) {
            System.out.println(">>> ALL SPIKE METRICS TESTS PASSED! <<<");
        } else {
            System.err.println(">>> SPIKE METRICS TESTS FAILED! <<<");
        }
        System.out.println("----------------------------------------------------------------------");
    }

    /**
     * Test 1: 1 job on 1 VM.
     */
    private static boolean testSingleJobSingleVm() {
        System.out.println("TEST 1: 1 Job on 1 VM");
        System.out.println("---------------------");

        double[] vmMips = {1000.0};
        List<SpikeMetrics.JobRecord> jobs = new ArrayList<>();
        // id=0, vm=0, mi=2000, arrival=0, dispatch=0, start=0, finish=2.0
        jobs.add(new SpikeMetrics.JobRecord(0, 0, 2000, 0.0, 0.0, 0.0, 2.0));

        SpikeMetrics.Params params = new SpikeMetrics.Params();
        SpikeMetrics.Result res = SpikeMetrics.compute(jobs, vmMips, Collections.emptyList(), params);

        double makespanSim = (Double) res.row.get("sim_makespan_s");
        double arur = (Double) res.row.get("arur_paper");
        double imbBusy = (Double) res.row.get("imbalance_busy_max_over_mean");
        double slowAssigned = (Double) res.row.get("slowdown_assigned_mean");

        System.out.printf("sim_makespan_s = %.2f (expected 2.00)\n", makespanSim);
        System.out.printf("arur_paper = %.2f (expected 1.00)\n", arur);
        System.out.printf("imbalance_busy_max_over_mean = %.2f (expected 1.00)\n", imbBusy);
        System.out.printf("slowdown_assigned_mean = %.2f (expected 1.00)\n", slowAssigned);

        boolean pass = Math.abs(makespanSim - 2.0) < 1e-6
                && Math.abs(arur - 1.0) < 1e-6
                && Math.abs(imbBusy - 1.0) < 1e-6
                && Math.abs(slowAssigned - 1.0) < 1e-6;

        System.out.println(pass ? "[PASS] Single job metrics match expected values." : "[FAIL] Single job mismatch!");
        return pass;
    }

    /**
     * Test 2: 2 jobs on 2 VMs with different speeds (Validates SM-1 and SM-2).
     *   VM 0: 2000 MIPS (fastest)
     *   VM 1: 1000 MIPS (slower)
     *
     *   Job 0: 2000 MI on VM 0 -> execution time = 1.0s (finish = 1.0s)
     *   Job 1: 3000 MI on VM 1 -> execution time = 3.0s (finish = 3.0s)
     *
     *   Expected:
     *     sumBusy = 1.0 + 3.0 = 4.0s
     *     meanBusy = 4.0 / 2 = 2.0s
     *     maxBusy = 3.0s
     *     imbalance_busy_max_over_mean = 3.0 / 2.0 = 1.50 (SM-1)
     *
     *     Assigned slowdown (SM-2):
     *       Job 0: 1.0 / (2000 / 2000) = 1.0
     *       Job 1: 3.0 / (3000 / 1000) = 1.0
     *       Mean assigned slowdown = 1.00
     *     Ideal slowdown (vs fastest VM 2000):
     *       Job 0: 1.0 / (2000 / 2000) = 1.0
     *       Job 1: 3.0 / (3000 / 2000) = 2.0
     *       Mean ideal slowdown = 1.50
     */
    private static boolean testTwoJobsHeterogeneousVms() {
        System.out.println("\nTEST 2: 2 Jobs on 2 Heterogeneous VMs (Validates SM-1 & SM-2)");
        System.out.println("-------------------------------------------------------------");

        double[] vmMips = {2000.0, 1000.0};
        List<SpikeMetrics.JobRecord> jobs = new ArrayList<>();
        jobs.add(new SpikeMetrics.JobRecord(0, 0, 2000, 0.0, 0.0, 0.0, 1.0));
        jobs.add(new SpikeMetrics.JobRecord(1, 1, 3000, 0.0, 0.0, 0.0, 3.0));

        SpikeMetrics.Params params = new SpikeMetrics.Params();
        SpikeMetrics.Result res = SpikeMetrics.compute(jobs, vmMips, Collections.emptyList(), params);

        double imbBusy = (Double) res.row.get("imbalance_busy_max_over_mean");
        double slowAssigned = (Double) res.row.get("slowdown_assigned_mean");
        double slowIdeal = (Double) res.row.get("slowdown_mean");

        System.out.printf("imbalance_busy_max_over_mean = %.3f (expected 1.500)\n", imbBusy);
        System.out.printf("slowdown_assigned_mean       = %.3f (expected 1.000)\n", slowAssigned);
        System.out.printf("slowdown_mean (ideal)        = %.3f (expected 1.500)\n", slowIdeal);

        boolean pass = Math.abs(imbBusy - 1.5) < 1e-6
                && Math.abs(slowAssigned - 1.0) < 1e-6
                && Math.abs(slowIdeal - 1.5) < 1e-6;

        System.out.println(pass ? "[PASS] SM-1 (imbalance) and SM-2 (assigned slowdown) validated correctly."
                                : "[FAIL] Metric calculation mismatch!");
        return pass;
    }

    /**
     * Test 3: Recovery parameter recording (Validates SM-3).
     */
    private static boolean testRecoveryParamsPreserved() {
        System.out.println("\nTEST 3: Recovery Parameters Preserved in Rows (Validates SM-3)");
        System.out.println("--------------------------------------------------------------");

        double[] vmMips = {1000.0};
        List<SpikeMetrics.JobRecord> jobs = new ArrayList<>();
        jobs.add(new SpikeMetrics.JobRecord(0, 0, 1000, 0.0, 0.0, 0.0, 1.0));

        SpikeMetrics.Params params = new SpikeMetrics.Params();
        params.recoveryTol = 0.30;
        params.recoverySigma = 2.5;
        params.recoveryFloorSec = 2.0;
        params.recoveryHoldSec = 45.0;

        SpikeMetrics.Result res = SpikeMetrics.compute(jobs, vmMips, Collections.emptyList(), params);

        double tol = (Double) res.row.get("recovery_tol");
        double sigma = (Double) res.row.get("recovery_sigma");
        double floor = (Double) res.row.get("recovery_floor_s");
        double hold = (Double) res.row.get("recovery_hold_s");

        System.out.printf("recovery_tol     = %.2f (expected 0.30)\n", tol);
        System.out.printf("recovery_sigma   = %.2f (expected 2.50)\n", sigma);
        System.out.printf("recovery_floor_s = %.2f (expected 2.00)\n", floor);
        System.out.printf("recovery_hold_s  = %.2f (expected 45.00)\n", hold);

        boolean pass = Math.abs(tol - 0.30) < 1e-6
                && Math.abs(sigma - 2.5) < 1e-6
                && Math.abs(floor - 2.0) < 1e-6
                && Math.abs(hold - 45.0) < 1e-6;

        System.out.println(pass ? "[PASS] SM-3 recovery parameters correctly present in row."
                                : "[FAIL] Parameters not found or incorrect!");
        return pass;
    }
}
