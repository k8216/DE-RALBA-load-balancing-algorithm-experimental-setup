# Comparative Performance Evaluation and Enhancement of DE-RALBA for Dynamic Workloads in Heterogeneous Cloud Environments

This repository contains the complete experimental framework, algorithms, validation suites, and analysis tools for evaluating and enhancing the **DE-RALBA** (Dynamic Enhanced Resource-Aware Load Balancing Algorithm, Hussain et al., PeerJ Computer Science 11:e2739, 2025) in heterogeneous cloud computing environments under steady and spiky traffic patterns.

The framework benchmarks DE-RALBA against **Round Robin (RR)**, **Earliest Finish Time (EFT)**, **Dynamic Max-Min**, and the **Author's Reference Implementation Replica** across 30 independent pseudo-random seeds and 6 distinct workload profiles.

---

## 1. System Architecture & Execution Model

Experiments execute on an exact discrete-event queue model adhering to **CloudSim Plus** single-PE, Space-Shared FIFO VM scheduling semantics:
- **Cluster Topology**: 50 heterogeneous virtual machines divided into 8 distinct processing tiers:
  - 7 VMs @ 100 MIPS
  - 7 VMs @ 500 MIPS
  - 6 VMs @ 550 MIPS
  - 6 VMs @ 1000 MIPS
  - 6 VMs @ 1250 MIPS
  - 6 VMs @ 1500 MIPS
  - 6 VMs @ 1750 MIPS
  - 6 VMs @ 2000 MIPS
  - **Total Cluster Capacity**: 52,500 MIPS.
- **Model Validation**: Validated against actual CloudSim Plus 8.5.7 executions via `CloudSimValidation.java`. Maximum per-job start and finish time error is bounded below **$1.49\text{ s}$** ($\le 0.16\%$ relative makespan error across $850\text{ s} - 7,760\text{ s}$ horizons), caused mainly by CloudSim Plus's default $0.10\text{ s}$ discrete event-clock resolution (`minTimeBetweenEvents`).

---

## 2. Workload Scenarios

Workloads are generated from the **GoCJ** (Google Cloud Jobs) trace pool (`GoCJ_100.txt`, 100 tasks, mean 135,095 MI, max 900,000 MI). Each experiment draws 1,000 jobs using cyclic pseudo-random shuffling:
1. **Steady $\rho = 0.5$**: Base arrival rate $\lambda \approx 0.1943\text{ jobs/s}$.
2. **Steady $\rho = 0.8$**: Arrival rate $\lambda \approx 0.3109\text{ jobs/s}$.
3. **Spike $\times 4$**: $10\text{ s}$ burst at $t = 1,000\text{ s}$ offering $\rho = 2.0$.
4. **Spike $\times 20$**: $10\text{ s}$ burst at $t = 1,000\text{ s}$ offering $\rho = 10.0$.
5. **Spike $\times 60$**: $10\text{ s}$ burst at $t = 1,000\text{ s}$ offering $\rho = 30.0$.
6. **Spike $\times 150$**: $10\text{ s}$ burst at $t = 1,000\text{ s}$ offering $\rho = 75.0$ ($\sim 291$ jobs arrive within $10\text{ s}$).

---

## 3. Evaluated Algorithms

- **`RR` (Round Robin)**: Static deterministic assignment (`jobId % 50`).
- **`EFT` (Earliest Finish Time)**: Dynamic greedy earliest completion time dispatch.
- **`MAX_MIN_T{1.0, 5.0, 30.0}`**: Dynamic Max-Min executed in scheduling rounds from current VM queue horizons.
- **`DERALBA_LIT`**: DE-RALBA literal pseudocode with proportional VM shares and idle-VM rebalancing.
- **`DERALBA_BF` (Big First)**: Enhanced DE-RALBA sorting round batches descending by job length (`BIG_FIRST`) to prevent slow-VM stragglers.
- **`DERALBA_BF_NOREB`**: Big First variant without idle-VM rebalancing (used for paired isolation analysis).
- **`DERALBA_LIT_BACKLOG`**: DE-RALBA with backlog-aware share calculation.
- **`DERALBA_AUTHOR_ASIS`**: Faithful reproduction of the authors' published code repository, reproducing discovered defects (ready-time doubling, off-by-one loops, and fake rebalancing).

---

## 4. How to Build & Run

### Prerequisites
- **Java JDK 21+** (Tested on JDK 26)
- **Apache Maven 3.9+** (or bundled with Apache NetBeans IDE)

### 1. Compile the Project
```powershell
mvn clean compile
```

### 2. Run the Validation Suites
- **CloudSim Plus Model Validation**:
  ```powershell
  mvn exec:java -Dexec.mainClass=edu.kunal.spike.lb.CloudSimValidation
  ```
- **Dynamic Max-Min Validation**:
  ```powershell
  mvn exec:java -Dexec.mainClass=edu.kunal.spike.lb.DynamicMaxMinValidation
  ```
- **Spike Metrics Unit Tests**:
  ```powershell
  mvn exec:java -Dexec.mainClass=edu.kunal.spike.lb.SpikeMetricsTest
  ```
- **Spike Recovery Sensitivity Analysis**:
  ```powershell
  mvn exec:java -Dexec.mainClass=edu.kunal.spike.lb.RecoverySensitivity
  ```

### 3. Run the Full Experiment Matrix (30 Seeds)
```powershell
mvn exec:java -Dexec.mainClass=edu.kunal.spike.lb.ExperimentRunner -Dexec.args="GoCJ_100.txt 30 results_new"
```

### 4. Compute Statistical Aggregations & Paired Differences
```powershell
mvn exec:java -Dexec.mainClass=edu.kunal.spike.lb.ExperimentAnalysis -Dexec.args="results/summary.csv results/aggregate_stats.csv results/paired_bf_vs_noreb.csv"
```

### 5. Generate Publication Figures
```powershell
python plot_figures.py
```
Outputs are written to `results/plots/`:
- `fig1_spike_backlog_dynamics.png`
- `fig2_response_time_vs_spike_intensity.png`
- `fig3_rebalancer_effect_sizes.png`
- `fig4_arur_and_imbalance.png`

---

## 5. CSV Output Schema

### `results/summary.csv` (Raw Run Records)
Each row records 1 run of 1 algorithm on 1 seed:
| Column | Unit | Description |
| :--- | :--- | :--- |
| `scenario` | string | Workload scenario identifier |
| `jobs` | count | Total cloudlets executed ($1,000$) |
| `seed` | integer | Random seed ($1 \dots 30$) |
| `algo` | string | Algorithm name |
| `round_len_s` | seconds | Round decision interval $T$ |
| `sim_makespan_s`| seconds | Simulator makespan (last job finish time) |
| `makespan_paper_s`| seconds | Makespan defined by Paper Eq. 29 (max per-VM busy duration) |
| `arur_paper` | ratio | Average Resource Utilization Ratio (Paper Eq. 13: mean busy / max busy) |
| `imbalance_busy_max_over_mean`| ratio | Load imbalance defined by Paper Eq. 32 (max busy / mean busy; 1.0 = ideal) |
| `mean_imbalance_active`| ratio | Time-averaged active backlog imbalance: $\frac{\text{max} - \text{mean}}{\text{mean}}$ (0.0 = ideal) |
| `throughput_jobs_per_s` | jobs/s | System throughput: $N / \text{makespan}$ |
| `resp_mean_s` | seconds | Mean job response time ($\text{finish} - \text{arrival}$) |
| `resp_p95_s` | seconds | 95th-percentile response time |
| `slowdown_assigned_mean` | ratio | Mean slowdown relative to assigned VM (Paper Eq. 30: $\text{response} / (\text{length} / \text{MIPS}_v)$) |
| `recovery_s` | seconds | Spike recovery duration (time until backlog remains below threshold for hold window) |
| `recovery_censored` | binary | $1$ if cluster did not recover before observation boundary (lower bound); $0$ otherwise |
| `recovery_hold_s` | seconds | Recovery hold window parameter ($60\text{ s}$) |
| `recovery_tol` | ratio | Recovery tolerance threshold parameter ($0.25$) |

### `results/aggregate_stats.csv` (Sample Statistics)
Contains `scenario`, `algo_variant`, `metric`, `n_seeds`, `censored_seeds`, `mean`, `std_dev`, `std_error`, and two-tailed `ci95_lower` / `ci95_upper` using Student's $t$-distribution ($df=29, t_{\text{crit}}=2.045$).

### `results/paired_bf_vs_noreb.csv` (Paired Effect Size)
Contains paired per-seed differences ($\text{DERALBA\_BF} - \text{DERALBA\_BF\_NOREB}$) evaluating the isolated impact of the idle-VM rebalancing mechanism.
## 6. Team

- Kunal Kashyap (2024UIT3126)
- Parna Hembram (2024UIT3115)


Department of Information Technology, Netaji Subhas University of Technology (NSUT), New Delhi
