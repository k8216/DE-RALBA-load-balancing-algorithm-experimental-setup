import os
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# Set publication style
plt.rcParams.update({
    'font.size': 11,
    'font.family': 'sans-serif',
    'axes.labelsize': 12,
    'axes.titlesize': 13,
    'xtick.labelsize': 10,
    'ytick.labelsize': 10,
    'legend.fontsize': 10,
    'figure.titlesize': 14,
    'lines.linewidth': 1.8,
    'grid.alpha': 0.4,
    'grid.linestyle': '--'
})

out_dir = r"c:\Users\kunal\Documents\NetBeansProjects\spike-lb\results\plots"
os.makedirs(out_dir, exist_ok=True)
series_dir = r"c:\Users\kunal\Documents\NetBeansProjects\spike-lb\results\series"
agg_file = r"c:\Users\kunal\Documents\NetBeansProjects\spike-lb\results\aggregate_stats.csv"
paired_file = r"c:\Users\kunal\Documents\NetBeansProjects\spike-lb\results\paired_bf_vs_noreb.csv"

# -----------------------------------------------------------------------------------------
# FIGURE 1: Spike x150 Backlog & Pending Jobs Dynamics (Seed 1)
# -----------------------------------------------------------------------------------------
fig, (ax1, ax2) = plt.subplots(2, 1, figsize=(10, 8), sharex=True)

algos = [
    ("EFT", f"{series_dir}/spike_x150__EFT__seed1.csv", "#2ca02c", "-"),
    ("DE-RALBA BF (T=5s)", f"{series_dir}/spike_x150__DERALBA_BF__T5.0__seed1.csv", "#1f77b4", "-"),
    ("Dynamic Max-Min (T=5s)", f"{series_dir}/spike_x150__MAX_MIN_T5.0__seed1.csv", "#ff7f0e", "-."),
    ("DE-RALBA Author As-Is", f"{series_dir}/spike_x150__DERALBA_AUTHOR_ASIS__T1.0__seed1.csv", "#d62728", "--"),
    ("Round Robin", f"{series_dir}/spike_x150__RR__seed1.csv", "#7f7f7f", ":")
]

for name, path, color, ls in algos:
    if os.path.exists(path):
        df = pd.read_csv(path)
        # Filter around the spike window: t in [900, 1600]
        df_sub = df[(df['time_s'] >= 900) & (df['time_s'] <= 1600)]
        ax1.plot(df_sub['time_s'], df_sub['cluster_backlog_s'], label=name, color=color, linestyle=ls)
        ax2.plot(df_sub['time_s'], df_sub['pending_jobs'], label=name, color=color, linestyle=ls)

ax1.set_ylabel("Cluster Backlog (s)")
ax1.set_title("Cluster Backlog Dynamics during Spike x150 (Burst at t = 1000s to 1010s)")
ax1.grid(True)
ax1.legend(loc='upper right', framealpha=0.9)

ax2.set_ylabel("Pending Jobs in System")
ax2.set_xlabel("Simulation Time (s)")
ax2.set_title("System Queue Drain Dynamics during Spike x150")
ax2.grid(True)

plt.tight_layout()
fig_path1 = os.path.join(out_dir, "fig1_spike_backlog_dynamics.png")
plt.savefig(fig_path1, dpi=300)
plt.close()
print(f"Generated: {fig_path1}")

# -----------------------------------------------------------------------------------------
# FIGURE 2: Response Times across Spike Multipliers
# -----------------------------------------------------------------------------------------
if os.path.exists(agg_file):
    agg_df = pd.read_csv(agg_file)
    spike_scenarios = ["spike_x4", "spike_x20", "spike_x60", "spike_x150"]
    multipliers = [4, 20, 60, 150]
    
    key_algos = [
        ("EFT", "#2ca02c", "o-"),
        ("DERALBA_BF (T=5.0s)", "#1f77b4", "s-"),
        ("MAX_MIN_T5.0", "#ff7f0e", "^-."),
        ("DERALBA_AUTHOR_ASIS (T=1.0s)", "#d62728", "x--"),
        ("RR", "#7f7f7f", "d:")
    ]
    
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 5))
    
    for algo_label, color, fmt in key_algos:
        mean_rt = []
        p95_rt = []
        for sc in spike_scenarios:
            sub_m = agg_df[(agg_df['scenario'] == sc) & (agg_df['algo_variant'] == algo_label) & (agg_df['metric'] == 'resp_mean_s')]
            sub_p = agg_df[(agg_df['scenario'] == sc) & (agg_df['algo_variant'] == algo_label) & (agg_df['metric'] == 'resp_p95_s')]
            mean_rt.append(sub_m['mean'].values[0] if len(sub_m) > 0 else np.nan)
            p95_rt.append(sub_p['mean'].values[0] if len(sub_p) > 0 else np.nan)
        
        ax1.plot(multipliers, mean_rt, fmt, color=color, label=algo_label, markersize=6)
        ax2.plot(multipliers, p95_rt, fmt, color=color, label=algo_label, markersize=6)
        
    ax1.set_xlabel("Spike Intensity Multiplier (x)")
    ax1.set_ylabel("Mean Response Time (s)")
    ax1.set_title("Mean Response Time vs Spike Intensity")
    ax1.grid(True)
    ax1.set_yscale('log')
    ax1.legend()
    
    ax2.set_xlabel("Spike Intensity Multiplier (x)")
    ax2.set_ylabel("95th-Percentile Response Time (s)")
    ax2.set_title("95th-Percentile Response Time vs Spike Intensity")
    ax2.grid(True)
    ax2.set_yscale('log')
    ax2.legend()
    
    plt.tight_layout()
    fig_path2 = os.path.join(out_dir, "fig2_response_time_vs_spike_intensity.png")
    plt.savefig(fig_path2, dpi=300)
    plt.close()
    print(f"Generated: {fig_path2}")

# -----------------------------------------------------------------------------------------
# FIGURE 3: Paired Rebalancer Difference (BF minus BF_NOREB) with 95% CIs
# -----------------------------------------------------------------------------------------
if os.path.exists(paired_file):
    paired_df = pd.read_csv(paired_file)
    
    # Filter for Spike x20 and Spike x60 across round lengths
    metrics = ["resp_p95_s", "recovery_s", "resp_mean_s"]
    metric_names = {"resp_p95_s": "Tail Latency (p95)", "recovery_s": "Spike Recovery", "resp_mean_s": "Mean Response"}
    
    sub = paired_df[(paired_df['scenario'].isin(["spike_x20", "spike_x60"])) & (paired_df['metric'].isin(metrics))]
    
    fig, axes = plt.subplots(1, 3, figsize=(14, 4.5), sharey=False)
    
    for idx, m in enumerate(metrics):
        ax = axes[idx]
        m_df = paired_df[paired_df['metric'] == m]
        
        # Scenarios to show
        sc_list = ["spike_x4", "spike_x20", "spike_x60", "spike_x150"]
        x = np.arange(len(sc_list))
        width = 0.25
        
        for t_idx, t_val in enumerate([1.0, 5.0, 30.0]):
            t_data = m_df[m_df['round_len_s'] == t_val]
            means = []
            errors = []
            for sc in sc_list:
                row = t_data[t_data['scenario'] == sc]
                if len(row) > 0:
                    means.append(row['mean_diff'].values[0])
                    errors.append(row['ci95_upper'].values[0] - row['mean_diff'].values[0])
                else:
                    means.append(0)
                    errors.append(0)
            
            offset = (t_idx - 1) * width
            ax.bar(x + offset, means, width, yerr=errors, capsize=3, label=f"T = {t_val}s", alpha=0.85)
            
        ax.set_xticks(x)
        ax.set_xticklabels(["Spike x4", "Spike x20", "Spike x60", "Spike x150"])
        ax.axhline(0, color='black', linewidth=0.8, linestyle='--')
        ax.set_title(f"Δ {metric_names[m]} (s)")
        ax.set_ylabel("Difference (BF - NOREB) (s)")
        ax.grid(True)
        if idx == 0:
            ax.legend()
            
    plt.suptitle("Paired Benefit of Idle-VM Rebalancing (Negative = Improvement)", fontsize=13)
    plt.tight_layout()
    fig_path3 = os.path.join(out_dir, "fig3_rebalancer_effect_sizes.png")
    plt.savefig(fig_path3, dpi=300)
    plt.close()
    print(f"Generated: {fig_path3}")

# -----------------------------------------------------------------------------------------
# FIGURE 4: ARUR & Imbalance Comparison (Steady vs Spike)
# -----------------------------------------------------------------------------------------
if os.path.exists(agg_file):
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(12, 5))
    
    scenarios = ["steady_rho0.5", "steady_rho0.8", "spike_x20", "spike_x150"]
    sc_labels = ["Steady 0.5", "Steady 0.8", "Spike x20", "Spike x150"]
    x = np.arange(len(scenarios))
    width = 0.16
    
    plot_algos = [
        ("EFT", "#2ca02c"),
        ("DERALBA_BF (T=5.0s)", "#1f77b4"),
        ("MAX_MIN_T5.0", "#ff7f0e"),
        ("DERALBA_AUTHOR_ASIS (T=1.0s)", "#d62728"),
        ("RR", "#7f7f7f")
    ]
    
    for idx, (algo, color) in enumerate(plot_algos):
        arur_vals = []
        imb_vals = []
        for sc in scenarios:
            sub_a = agg_df[(agg_df['scenario'] == sc) & (agg_df['algo_variant'] == algo) & (agg_df['metric'] == 'arur_paper')]
            sub_i = agg_df[(agg_df['scenario'] == sc) & (agg_df['algo_variant'] == algo) & (agg_df['metric'] == 'imbalance_busy_max_over_mean')]
            arur_vals.append(sub_a['mean'].values[0] if len(sub_a) > 0 else np.nan)
            imb_vals.append(sub_i['mean'].values[0] if len(sub_i) > 0 else np.nan)
            
        offset = (idx - 2) * width
        ax1.bar(x + offset, arur_vals, width, label=algo, color=color, alpha=0.85)
        ax2.bar(x + offset, imb_vals, width, label=algo, color=color, alpha=0.85)
        
    ax1.set_xticks(x)
    ax1.set_xticklabels(sc_labels)
    ax1.set_ylabel("ARUR Utilization (Eq. 13)")
    ax1.set_title("Average Resource Utilization Ratio (Higher is Better)")
    ax1.grid(True)
    ax1.legend(loc='lower right', fontsize=8.5)
    
    ax2.set_xticks(x)
    ax2.set_xticklabels(sc_labels)
    ax2.set_ylabel("Busy Imbalance Max / Mean (Eq. 32)")
    ax2.set_title("Resource Load Imbalance (1.0 = Perfectly Even)")
    ax2.grid(True)
    ax2.legend(loc='upper right', fontsize=8.5)
    
    plt.tight_layout()
    fig_path4 = os.path.join(out_dir, "fig4_arur_and_imbalance.png")
    plt.savefig(fig_path4, dpi=300)
    plt.close()
    print(f"Generated: {fig_path4}")

print("All publication figures successfully created in results/plots/.")
