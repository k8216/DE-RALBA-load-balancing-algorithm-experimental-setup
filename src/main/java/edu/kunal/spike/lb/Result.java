
package edu.kunal.spike.lb;

public record Result(double meanResp, double p95, double p99, double makespan,
                     double utilization, double imbalance, double peakBacklogMI, double decisionMs) {
    public double[] vec() {
        return new double[]{meanResp, p95, p99, makespan, utilization, imbalance, peakBacklogMI, decisionMs};
    }
}