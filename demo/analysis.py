"""
Causal effect estimators for the multi-site NSCLC demo.

Three layers:
  1. Classical per-site / pooled estimators (crude, IPW, g-computation, AIPW)
     with bootstrap CIs.
  2. DoWhy wrappers for identification, estimation and refutation.
  3. PyMC models: a hierarchical logistic outcome model with partially pooled
     site-specific treatment effects, a single-site model for the federated
     variant, and a random-effects meta-analysis that combines site summaries.

All effects are reported as risk differences (RD) for 12-month progression,
osimertinib minus platinum doublet, in the EGFR-positive subgroup unless noted.
"""

from __future__ import annotations

import json
from pathlib import Path

import arviz as az
import networkx as nx
import numpy as np
import pandas as pd
import pymc as pm
import pytensor.tensor as pt
import statsmodels.api as sm
from dowhy import CausalModel
from sklearn.linear_model import LogisticRegression

COVARIATES = ["age", "female", "stage_iv", "ecog_ge2", "albumin", "never_smoker"]
TREATMENT = "treatment"
OUTCOME = "progression_12m"
SITES = ("aws", "azure", "gcp")


# ---------------------------------------------------------------------------
# Layer 1: classical estimators
# ---------------------------------------------------------------------------

def crude_rd(df: pd.DataFrame) -> dict:
    t = df[TREATMENT] == 1
    p1, p0 = df.loc[t, OUTCOME].mean(), df.loc[~t, OUTCOME].mean()
    return {"rd": p1 - p0, "p_treated": p1, "p_control": p0, "n_treated": int(t.sum()), "n_control": int((~t).sum())}


def _design(df: pd.DataFrame, covariates: list[str]) -> np.ndarray:
    return sm.add_constant(df[covariates].to_numpy(dtype=float), has_constant="add")


def fit_propensity(df: pd.DataFrame, covariates: list[str]) -> np.ndarray:
    lr = LogisticRegression(C=1e6, max_iter=1000)
    lr.fit(df[covariates].to_numpy(dtype=float), df[TREATMENT].to_numpy())
    return lr.predict_proba(df[covariates].to_numpy(dtype=float))[:, 1]


def _ipw(df: pd.DataFrame, covariates: list[str], clip: tuple[float, float]) -> float:
    ps = np.clip(fit_propensity(df, covariates), *clip)
    t, y = df[TREATMENT].to_numpy(), df[OUTCOME].to_numpy()
    w1, w0 = t / ps, (1 - t) / (1 - ps)
    return float(np.sum(w1 * y) / np.sum(w1) - np.sum(w0 * y) / np.sum(w0))


def _fit_outcome(df: pd.DataFrame, covariates: list[str]):
    X = sm.add_constant(df[[TREATMENT] + covariates].to_numpy(dtype=float), has_constant="add")
    return sm.GLM(df[OUTCOME].to_numpy(), X, family=sm.families.Binomial()).fit()


def _gcomp(df: pd.DataFrame, covariates: list[str], clip: tuple[float, float] = (0.0, 1.0)) -> float:
    res = _fit_outcome(df, covariates)
    Xc = df[covariates].to_numpy(dtype=float)
    n = len(df)
    X1 = np.column_stack([np.ones(n), np.ones(n), Xc])
    X0 = np.column_stack([np.ones(n), np.zeros(n), Xc])
    return float(np.mean(res.predict(X1) - res.predict(X0)))


def _aipw(df: pd.DataFrame, covariates: list[str], clip: tuple[float, float]) -> float:
    ps = np.clip(fit_propensity(df, covariates), *clip)
    res = _fit_outcome(df, covariates)
    Xc = df[covariates].to_numpy(dtype=float)
    n = len(df)
    m1 = res.predict(np.column_stack([np.ones(n), np.ones(n), Xc]))
    m0 = res.predict(np.column_stack([np.ones(n), np.zeros(n), Xc]))
    t, y = df[TREATMENT].to_numpy(), df[OUTCOME].to_numpy()
    psi = (t * (y - m1) / ps + m1) - ((1 - t) * (y - m0) / (1 - ps) + m0)
    return float(np.mean(psi))


ESTIMATORS = {
    "crude": lambda d, c, clip: crude_rd(d)["rd"],
    "ipw": _ipw,
    "gcomp": _gcomp,
    "aipw": _aipw,
}


def bootstrap_estimate(
    df: pd.DataFrame,
    method: str,
    covariates: list[str] = COVARIATES,
    n_boot: int = 200,
    seed: int = 0,
    clip: tuple[float, float] = (0.02, 0.98),
) -> dict:
    """Point estimate plus percentile bootstrap 95% CI for one estimator."""
    fn = ESTIMATORS[method]
    rng = np.random.default_rng(seed)
    point = fn(df, covariates, clip)
    boots = []
    for _ in range(n_boot):
        sample = df.sample(len(df), replace=True, random_state=int(rng.integers(2**31)))
        if sample[TREATMENT].nunique() < 2:
            continue
        try:
            boots.append(fn(sample, covariates, clip))
        except Exception:
            continue
    lo, hi = np.percentile(boots, [2.5, 97.5]) if boots else (np.nan, np.nan)
    return {"method": method, "estimate": point, "ci_low": float(lo), "ci_high": float(hi), "n": len(df)}


def estimate_all(
    df: pd.DataFrame, covariates: list[str] = COVARIATES, methods: tuple[str, ...] = tuple(ESTIMATORS), **kw
) -> pd.DataFrame:
    return pd.DataFrame([bootstrap_estimate(df, m, covariates, **kw) for m in methods])


def estimate_by_site(df: pd.DataFrame, covariates: list[str] = COVARIATES, **kw) -> pd.DataFrame:
    frames = []
    for site, sub in df.groupby("site"):
        out = estimate_all(sub, covariates, **kw)
        out.insert(0, "site", site)
        frames.append(out)
    return pd.concat(frames, ignore_index=True)


def overlap_table(df: pd.DataFrame, covariates: list[str] = COVARIATES) -> pd.DataFrame:
    """Propensity-score summary by site and arm for positivity checks."""
    rows = []
    for site, sub in df.groupby("site"):
        ps = fit_propensity(sub, covariates)
        for arm, label in ((1, "osimertinib"), (0, "chemo")):
            m = sub[TREATMENT].to_numpy() == arm
            rows.append({
                "site": site, "arm": label, "n": int(m.sum()),
                "ps_min": ps[m].min(), "ps_p05": np.percentile(ps[m], 5), "ps_median": np.median(ps[m]),
                "ps_p95": np.percentile(ps[m], 95), "ps_max": ps[m].max(),
            })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# Layer 2: DoWhy
# ---------------------------------------------------------------------------

def causal_graph(confounders: list[str] = COVARIATES) -> nx.DiGraph:
    """Assumed DAG (mirrors the generator's structure) as a networkx DiGraph."""
    g = nx.DiGraph()
    g.add_edge(TREATMENT, OUTCOME)
    for c in confounders:
        g.add_edge(c, TREATMENT)
        g.add_edge(c, OUTCOME)
    structural = [("stage_iv", "ecog_ge2"), ("ecog_ge2", "albumin"), ("stage_iv", "albumin"), ("age", "ecog_ge2")]
    for a, b in structural:
        if a in confounders and b in confounders:
            g.add_edge(a, b)
    return g


def causal_graph_dot(confounders: list[str] = COVARIATES) -> str:
    edges = [f"{a} -> {b};" for a, b in causal_graph(confounders).edges]
    return "digraph {\n  " + "\n  ".join(edges) + "\n}"


def dowhy_model(df: pd.DataFrame, covariates: list[str] = COVARIATES, include_site: bool = False):
    """Build a DoWhy CausalModel and identify the effect via the back-door criterion."""
    cols = [TREATMENT, OUTCOME] + covariates + (["site"] if include_site else [])
    data = df[cols].copy()
    confounders = list(covariates)
    if include_site:
        data = pd.get_dummies(data, columns=["site"], drop_first=True, dtype=float)
        confounders += [c for c in data.columns if c.startswith("site_")]
    model = CausalModel(data=data, treatment=TREATMENT, outcome=OUTCOME, graph=causal_graph(confounders))
    estimand = model.identify_effect(proceed_when_unidentifiable=True)
    return model, estimand


DOWHY_METHODS = {
    "dowhy_ipw": ("backdoor.propensity_score_weighting", {"weighting_scheme": "ips_weight"}),
    "dowhy_regression": ("backdoor.linear_regression", {}),
    "dowhy_stratification": ("backdoor.propensity_score_stratification", {}),
}


def dowhy_estimates(model: CausalModel, estimand, methods: dict = DOWHY_METHODS) -> tuple[pd.DataFrame, dict]:
    rows, estimates = [], {}
    for label, (method, params) in methods.items():
        est = model.estimate_effect(
            estimand, method_name=method, target_units="ate", method_params=params, confidence_intervals=True
        )
        ci = est.get_confidence_intervals()
        ci = np.asarray(ci).ravel() if ci is not None else (np.nan, np.nan)
        rows.append({"method": label, "estimate": float(est.value), "ci_low": float(ci[0]), "ci_high": float(ci[1])})
        estimates[label] = est
    return pd.DataFrame(rows), estimates


def dowhy_refutations(model: CausalModel, estimand, estimate, seed: int = 0) -> pd.DataFrame:
    """Standard refuters; a robust estimate passes all of them."""
    specs = [
        ("placebo_treatment", "placebo_treatment_refuter", {"placebo_type": "permute", "num_simulations": 50}),
        ("random_common_cause", "random_common_cause", {"num_simulations": 50}),
        ("data_subset_80pct", "data_subset_refuter", {"subset_fraction": 0.8, "num_simulations": 50}),
        ("unobserved_confounder", "add_unobserved_common_cause", {
            "confounders_effect_on_treatment": "binary_flip",
            "confounders_effect_on_outcome": "binary_flip",
            "effect_strength_on_treatment": 0.05,
            "effect_strength_on_outcome": 0.05,
        }),
    ]
    rows = []
    for label, method, kw in specs:
        ref = model.refute_estimate(estimand, estimate, method_name=method, random_seed=seed, **kw)
        rows.append({
            "refuter": label,
            "original": float(ref.estimated_effect),
            "new_effect": float(np.ravel(ref.new_effect)[0]) if ref.new_effect is not None else np.nan,
            "p_value": float(ref.refutation_result["p_value"]) if isinstance(ref.refutation_result, dict)
            and "p_value" in ref.refutation_result else np.nan,
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# Layer 3: PyMC
# ---------------------------------------------------------------------------

def _standardise(df: pd.DataFrame, covariates: list[str]) -> np.ndarray:
    X = df[covariates].to_numpy(dtype=float)
    mu, sd = X.mean(0), X.std(0)
    sd[sd == 0] = 1.0
    return (X - mu) / sd


def fit_hierarchical(
    df: pd.DataFrame,
    covariates: list[str] = COVARIATES,
    draws: int = 1000,
    tune: int = 1000,
    chains: int = 4,
    target_accept: float = 0.95,
    seed: int = 0,
    prior_only: bool = False,
) -> az.InferenceData:
    """Hierarchical logistic model with partially pooled EGFR+ treatment effects by site.

    Posterior deterministics:
      tau_site[s]     site-specific log-odds effect in EGFR+
      mu_tau          pooled log-odds effect in EGFR+
      tau_neg         effect in EGFR- (negative control)
      ate_rd_egfr_pos g-computed risk difference in EGFR+ (pooled over sites)
      ate_rd_site[s]  g-computed risk difference in EGFR+ within site s
    """
    sites = sorted(df["site"].unique())
    s_idx = pd.Categorical(df["site"], categories=sites).codes
    X = _standardise(df, covariates)
    t = df[TREATMENT].to_numpy(dtype=float)
    e = df["egfr_positive"].to_numpy(dtype=float)
    y = df[OUTCOME].to_numpy()
    S = np.eye(len(sites))[s_idx]

    coords = {"site": sites, "covariate": covariates, "obs": np.arange(len(df))}
    with pm.Model(coords=coords) as model:
        mu_alpha = pm.Normal("mu_alpha", 0.0, 1.5)
        sigma_alpha = pm.HalfNormal("sigma_alpha", 1.0)
        z_alpha = pm.Normal("z_alpha", 0.0, 1.0, dims="site")
        alpha = pm.Deterministic("alpha_site", mu_alpha + sigma_alpha * z_alpha, dims="site")

        mu_tau = pm.Normal("mu_tau", 0.0, 1.0)
        sigma_tau = pm.HalfNormal("sigma_tau", 0.5)
        z_tau = pm.Normal("z_tau", 0.0, 1.0, dims="site")
        tau = pm.Deterministic("tau_site", mu_tau + sigma_tau * z_tau, dims="site")

        tau_neg = pm.Normal("tau_neg", 0.0, 1.0)
        beta = pm.Normal("beta", 0.0, 1.0, dims="covariate")
        beta_egfr = pm.Normal("beta_egfr", 0.0, 1.0)

        eta_base = alpha[s_idx] + pt.dot(X, beta) + beta_egfr * e
        tau_row = tau[s_idx] * e + tau_neg * (1.0 - e)
        eta = eta_base + tau_row * t
        pm.Bernoulli("y", logit_p=eta, observed=y, dims="obs")

        rd = pm.math.sigmoid(eta_base + tau_row) - pm.math.sigmoid(eta_base)
        pm.Deterministic("ate_rd_egfr_pos", pt.sum(rd * e) / pt.sum(e))
        pm.Deterministic("ate_rd_egfr_neg", pt.sum(rd * (1 - e)) / pt.sum(1 - e))
        pm.Deterministic("ate_rd_site", pt.dot(S.T, rd * e) / pt.dot(S.T, e), dims="site")

        if prior_only:
            return pm.sample_prior_predictive(draws=draws, random_seed=seed)
        idata = pm.sample(
            draws=draws, tune=tune, chains=chains, target_accept=target_accept, random_seed=seed, progressbar=False
        )
        idata.extend(pm.sample_posterior_predictive(idata, random_seed=seed, progressbar=False))
    return idata


def fit_site_model(
    df_site: pd.DataFrame,
    covariates: list[str] = COVARIATES,
    draws: int = 1000,
    tune: int = 1000,
    chains: int = 4,
    seed: int = 0,
) -> az.InferenceData:
    """Single-site Bayesian logistic model (no pooling). Run locally at each site."""
    X = _standardise(df_site, covariates)
    t = df_site[TREATMENT].to_numpy(dtype=float)
    e = df_site["egfr_positive"].to_numpy(dtype=float)
    y = df_site[OUTCOME].to_numpy()

    with pm.Model(coords={"covariate": covariates}):
        alpha = pm.Normal("alpha", 0.0, 1.5)
        tau = pm.Normal("tau", 0.0, 1.0)
        tau_neg = pm.Normal("tau_neg", 0.0, 1.0)
        beta = pm.Normal("beta", 0.0, 1.0, dims="covariate")
        beta_egfr = pm.Normal("beta_egfr", 0.0, 1.0)
        eta_base = alpha + pt.dot(X, beta) + beta_egfr * e
        tau_row = tau * e + tau_neg * (1.0 - e)
        pm.Bernoulli("y", logit_p=eta_base + tau_row * t, observed=y)
        rd = pm.math.sigmoid(eta_base + tau_row) - pm.math.sigmoid(eta_base)
        pm.Deterministic("ate_rd_egfr_pos", pt.sum(rd * e) / pt.sum(e))
        return pm.sample(draws=draws, tune=tune, chains=chains, target_accept=0.9, random_seed=seed, progressbar=False)


def site_summary(idata: az.InferenceData, site: str, var: str = "ate_rd_egfr_pos") -> dict:
    """The only thing a site shares in the federated variant: a posterior mean and sd."""
    x = idata.posterior[var].to_numpy().ravel()
    return {"site": site, "mean": float(x.mean()), "sd": float(x.std()), "n_draws": int(x.size)}


def meta_analysis(
    summaries: list[dict], draws: int = 2000, tune: int = 1000, chains: int = 4, seed: int = 0
) -> az.InferenceData:
    """Bayesian random-effects meta-analysis of site-level posterior summaries."""
    means = np.array([s["mean"] for s in summaries])
    sds = np.array([s["sd"] for s in summaries])
    sites = [s["site"] for s in summaries]
    with pm.Model(coords={"site": sites}):
        mu = pm.Normal("mu", 0.0, 0.5)
        tau_between = pm.HalfNormal("tau_between", 0.1)
        z = pm.Normal("z", 0.0, 1.0, dims="site")
        theta = pm.Deterministic("theta_site", mu + tau_between * z, dims="site")
        pm.Normal("obs", mu=theta, sigma=sds, observed=means, dims="site")
        return pm.sample(draws=draws, tune=tune, chains=chains, target_accept=0.95, random_seed=seed, progressbar=False)


def posterior_interval(idata: az.InferenceData, var: str, hdi_prob: float = 0.95) -> dict:
    x = idata.posterior[var]
    hdi = az.hdi(x, hdi_prob=hdi_prob)[var].to_numpy()
    return {"estimate": float(x.mean()), "ci_low": float(hdi[0]), "ci_high": float(hdi[1])}


def convergence_summary(idata: az.InferenceData, var_names: list[str]) -> pd.DataFrame:
    summ = az.summary(idata, var_names=var_names, round_to=4)
    diverging = int(idata.sample_stats["diverging"].sum()) if "diverging" in idata.sample_stats else 0
    summ.attrs["divergences"] = diverging
    return summ


# ---------------------------------------------------------------------------
# Ground truth (benchmarking only)
# ---------------------------------------------------------------------------

def load_truth(root: str | Path, sites: tuple[str, ...] = SITES) -> pd.DataFrame:
    frames = []
    for s in sites:
        df = pd.read_csv(Path(root) / s / "ground_truth.csv")
        df["site"] = s
        frames.append(df)
    return pd.concat(frames, ignore_index=True)


def load_truth_meta(root: str | Path, sites: tuple[str, ...] = SITES) -> dict:
    return {s: json.loads((Path(root) / s / "ground_truth.json").read_text()) for s in sites}


def true_rd(truth: pd.DataFrame, cohort_ids: pd.Series, egfr: int | None = 1) -> float:
    """Sample-average true RD (mean p1 - p0) restricted to the analysed patients."""
    sub = truth.loc[truth["patient_id"].isin(cohort_ids)]
    if egfr is not None:
        sub = sub.loc[sub["egfr_positive"] == egfr]
    return float((sub["p1"] - sub["p0"]).mean())


def score_estimates(estimates: pd.DataFrame, truth_value: float) -> pd.DataFrame:
    out = estimates.copy()
    out["truth"] = truth_value
    out["bias"] = out["estimate"] - truth_value
    out["covered"] = (out["ci_low"] <= truth_value) & (truth_value <= out["ci_high"])
    out["ci_width"] = out["ci_high"] - out["ci_low"]
    return out
