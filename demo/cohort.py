"""
Per-site cohort derivation for the NSCLC causal-inference demo.

Implements the protocol in analytical_plan.md section 3.1. Runs entirely on one
site's flattened FHIR tables (patients / conditions / observations /
medication_requests) and produces a single analytical table. Ground-truth files
are deliberately not read here.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

NSCLC_SNOMED = "254637007"
ADVANCED_STAGES = ("Stage 3B", "Stage 4")

LOINC_EGFR = "21667-1"
LOINC_ECOG = "89247-1"
LOINC_SMOKING = "72166-2"
LOINC_ALBUMIN = "1751-7"
LOINC_HEMOGLOBIN = "718-7"
LOINC_RESPONSE = "88040-1"

RX_OSIMERTINIB = "1721560"
RX_CARBOPLATIN = "40048"
RX_PEMETREXED = "477467"
RX_PACLITAXEL = "589511"
CHEMO_CODES = {RX_CARBOPLATIN, RX_PEMETREXED, RX_PACLITAXEL}

FOLLOW_UP_DAYS = 365
ARM_AMBIGUITY_WINDOW_DAYS = 30

SMOKING_MAP = {"Never smoked tobacco": "never", "Ex-smoker": "former", "Smoker": "current"}

TABLES = ("patients", "conditions", "observations", "medication_requests")


def load_site(path: str | Path) -> dict[str, pd.DataFrame]:
    """Load the four flattened FHIR CSV tables for one site."""
    path = Path(path)
    tables = {name: pd.read_csv(path / f"{name}.csv") for name in TABLES}
    for col in ("birth_date", "deceased_datetime"):
        tables["patients"][col] = pd.to_datetime(tables["patients"][col], utc=True, errors="coerce")
    tables["conditions"]["onset_datetime"] = pd.to_datetime(tables["conditions"]["onset_datetime"], utc=True)
    tables["observations"]["effective_datetime"] = pd.to_datetime(
        tables["observations"]["effective_datetime"], utc=True
    )
    tables["medication_requests"]["authored_on"] = pd.to_datetime(
        tables["medication_requests"]["authored_on"], utc=True
    )
    return tables


def _index_patients(patients: pd.DataFrame, conditions: pd.DataFrame, advanced_only: bool) -> pd.DataFrame:
    cond = conditions.loc[conditions["snomed_code"].astype(str) == NSCLC_SNOMED]
    if advanced_only:
        cond = cond.loc[cond["stage"].isin(ADVANCED_STAGES)]
    cond = cond.sort_values("onset_datetime").drop_duplicates("patient_id", keep="first")
    idx = patients.merge(
        cond[["patient_id", "condition_id", "stage", "onset_datetime"]], on="patient_id", how="inner"
    )
    idx = idx.rename(columns={"onset_datetime": "diagnosis_datetime", "managing_organization": "site"})
    return idx


def _assign_treatment(idx: pd.DataFrame, meds: pd.DataFrame) -> pd.DataFrame:
    """First-line arm from the earliest MedicationRequest(s); drop ambiguous / untreated patients."""
    meds = meds.loc[meds["patient_id"].isin(idx["patient_id"])].copy()
    meds["rxnorm_code"] = meds["rxnorm_code"].astype(str)
    meds["is_osi"] = meds["rxnorm_code"] == RX_OSIMERTINIB
    meds["is_chemo"] = meds["rxnorm_code"].isin(CHEMO_CODES)
    meds = meds.loc[meds["is_osi"] | meds["is_chemo"]]

    first = meds.groupby("patient_id")["authored_on"].min().rename("treatment_start")
    meds = meds.merge(first, on="patient_id")
    window = meds["authored_on"] <= meds["treatment_start"] + pd.Timedelta(days=ARM_AMBIGUITY_WINDOW_DAYS)
    early = meds.loc[window]

    arms = early.groupby("patient_id").agg(
        any_osi=("is_osi", "any"), any_chemo=("is_chemo", "any"), treatment_start=("treatment_start", "first")
    )
    arms = arms.loc[arms["any_osi"] != arms["any_chemo"]]
    arms["treatment"] = arms["any_osi"].astype(int)
    return idx.merge(arms[["treatment", "treatment_start"]], on="patient_id", how="inner")


def _last_before(obs: pd.DataFrame, cohort: pd.DataFrame, loinc: str, value_col: str, out_col: str) -> pd.Series:
    """Last observation of a LOINC code on or before treatment_start, per patient."""
    sub = obs.loc[obs["loinc_code"].astype(str) == loinc, ["patient_id", "effective_datetime", value_col]]
    sub = sub.merge(cohort[["patient_id", "treatment_start"]], on="patient_id")
    sub = sub.loc[sub["effective_datetime"] <= sub["treatment_start"]]
    sub = sub.sort_values("effective_datetime").drop_duplicates("patient_id", keep="last")
    return sub.set_index("patient_id")[value_col].rename(out_col)


def _baseline_covariates(cohort: pd.DataFrame, obs: pd.DataFrame) -> pd.DataFrame:
    egfr = _last_before(obs, cohort, LOINC_EGFR, "value_text", "egfr_text")
    ecog = _last_before(obs, cohort, LOINC_ECOG, "value", "ecog")
    smoking = _last_before(obs, cohort, LOINC_SMOKING, "value_text", "smoking_text")
    albumin = _last_before(obs, cohort, LOINC_ALBUMIN, "value", "albumin")
    hemoglobin = _last_before(obs, cohort, LOINC_HEMOGLOBIN, "value", "hemoglobin")

    cohort = cohort.set_index("patient_id")
    cohort = cohort.join([egfr, ecog, smoking, albumin, hemoglobin]).reset_index()

    cohort["egfr_positive"] = cohort["egfr_text"].map({"Positive": 1, "Negative": 0})
    cohort["smoking_status"] = cohort["smoking_text"].map(SMOKING_MAP)
    cohort["ecog"] = pd.to_numeric(cohort["ecog"], errors="coerce")
    cohort["albumin"] = pd.to_numeric(cohort["albumin"], errors="coerce")
    cohort["hemoglobin"] = pd.to_numeric(cohort["hemoglobin"], errors="coerce")
    return cohort.drop(columns=["egfr_text", "smoking_text"])


def _outcomes(cohort: pd.DataFrame, obs: pd.DataFrame) -> pd.DataFrame:
    resp = obs.loc[
        (obs["loinc_code"].astype(str) == LOINC_RESPONSE) & (obs["value_text"] == "Progressive disease"),
        ["patient_id", "effective_datetime"],
    ]
    prog = resp.groupby("patient_id")["effective_datetime"].min().rename("progression_datetime")
    cohort = cohort.merge(prog, on="patient_id", how="left")

    horizon = cohort["treatment_start"] + pd.Timedelta(days=FOLLOW_UP_DAYS)
    prog_days = (cohort["progression_datetime"] - cohort["treatment_start"]).dt.days
    death_days = (cohort["deceased_datetime"] - cohort["treatment_start"]).dt.days

    event_days = pd.concat([prog_days, death_days], axis=1).min(axis=1)
    within = event_days.notna() & (event_days <= FOLLOW_UP_DAYS)

    cohort["progression_12m"] = within.astype(int)
    cohort["event_days"] = np.where(within, event_days, FOLLOW_UP_DAYS).astype(int)
    cohort["death_12m"] = (death_days.notna() & (death_days <= FOLLOW_UP_DAYS)).astype(int)
    cohort["follow_up_end"] = horizon
    return cohort.drop(columns=["progression_datetime"])


def build_lung_cohort(tables: dict[str, pd.DataFrame], advanced_only: bool = True) -> pd.DataFrame:
    """Derive the analytical cohort table for one site.

    One row per eligible patient with baseline covariates measured on or before
    treatment start, a binary first-line treatment indicator (1 = osimertinib,
    0 = platinum doublet) and 12-month progression/death outcomes.
    """
    idx = _index_patients(tables["patients"], tables["conditions"], advanced_only)
    cohort = _assign_treatment(idx, tables["medication_requests"])
    cohort = _baseline_covariates(cohort, tables["observations"])
    cohort = _outcomes(cohort, tables["observations"])

    cohort["age"] = ((cohort["diagnosis_datetime"] - cohort["birth_date"]).dt.days / 365.25).round(1)
    cohort["female"] = (cohort["gender"] == "female").astype(int)
    cohort["stage_iv"] = (cohort["stage"] == "Stage 4").astype(int)
    cohort["ecog_ge2"] = (cohort["ecog"] >= 2).astype(int)
    cohort["never_smoker"] = (cohort["smoking_status"] == "never").astype(int)

    cols = [
        "patient_id", "site", "age", "female", "stage", "stage_iv",
        "egfr_positive", "ecog", "ecog_ge2", "albumin", "hemoglobin", "smoking_status", "never_smoker",
        "treatment", "treatment_start", "progression_12m", "event_days", "death_12m",
        "diagnosis_datetime", "follow_up_end",
    ]
    return cohort[cols].sort_values("patient_id").reset_index(drop=True)


def write_site_artifact(cohort: pd.DataFrame, path: str | Path) -> Path:
    """Write the cohort table to parquet; this is the only artefact shared beyond the site."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    cohort.to_parquet(path, index=False)
    return path


def build_all_sites(root: str | Path, sites: tuple[str, ...] = ("aws", "azure", "gcp")) -> pd.DataFrame:
    """Convenience: build and stack cohorts for several site directories under root."""
    root = Path(root)
    frames = [build_lung_cohort(load_site(root / s)) for s in sites]
    return pd.concat(frames, ignore_index=True)
