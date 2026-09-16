"""
Synthetic FHIR R4 EHR data generator for non-small cell lung cancer (NSCLC) patients.

Generates FHIR-compliant resources per https://hl7.org/fhir/ :
  - Patient
  - Condition        (problems  -> Condition resource, ICD-10 + SNOMED CT codings)
  - Observation      (vitals, labs, tumour markers, ECOG, smoking, response -> LOINC codings)
  - MedicationRequest (medications -> RxNorm codings)

Patients are generated from a structural causal model so that
treatment choice (osimertinib vs platinum doublet) is confounded by EGFR
status, age, stage, performance status and albumin, and 12-month progression
is a known function of treatment and covariates. Potential outcomes and model
parameters are written to ground_truth.csv / ground_truth.json so that causal
estimators can be benchmarked.

Output: structured CSV tables in the output directory, one per resource
type: patients.csv, conditions.csv, observations.csv,
medication_requests.csv. Tables are linked via patient_id / condition_id.

Usage:
    python generate_synthetic_ehr.py --patients 800 --site aws \
        --unmeasured-confounding none --output ./output/aws --seed 1


Examples:

python generate_synthetic_ehr.py --patients 800 --site westmids --seed 1 --output output/westmids 
python generate_synthetic_ehr.py --patients 800 --site tvs --seed 2 --output output/tvs
python generate_synthetic_ehr.py --patients 800 --site wessex --seed 3 --output output/wessex
python generate_synthetic_ehr.py --patients 800 --site eastern --seed 4 --output output/eastern

"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import fastparquet as fp
import pandas as pd

# ---------------------------------------------------------------------------
# Reference terminology
# ---------------------------------------------------------------------------

SNOMED = "http://snomed.info/sct"
ICD10 = "http://hl7.org/fhir/sid/icd-10"
LOINC = "http://loinc.org"
RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm"
UCUM = "http://unitsofmeasure.org"

# Lung-cohort specific observations
LOINC_ECOG = ("89247-1", "ECOG Performance Status score")
LOINC_SMOKING = ("72166-2", "Tobacco smoking status")
LOINC_RESPONSE = ("88040-1", "Response to cancer treatment")
SMOKING_SNOMED = {
    "never": ("266919005", "Never smoked tobacco"),
    "former": ("8517006", "Ex-smoker"),
    "current": ("77176002", "Smoker"),
}
RESPONSE_LOINC_ANSWERS = {
    "progressive": ("LA28370-7", "Progressive disease"),
    "stable": ("LA28366-5", "Stable disease"),
}

OSIMERTINIB = ("1721560", "osimertinib 80 MG Oral Tablet")
CARBOPLATIN = ("40048", "carboplatin")
PEMETREXED = ("477467", "pemetrexed")
PACLITAXEL = ("589511", "paclitaxel 6 MG/ML Injectable Solution")
PEMBROLIZUMAB = ("1547220", "pembrolizumab 25 MG/ML Injectable Solution")

# ---------------------------------------------------------------------------
# Site configuration and lung structural causal model
# ---------------------------------------------------------------------------

# Each site differs in prescribing culture (treat_offset), baseline progression
# risk (outcome_offset) and case-mix (smoking prevalence, stage distribution).
# The true treatment effect is shared across sites.
SITES = {
    "default": {
        "treat_offset": 0.0, "outcome_offset": 0.0, "never_smoker_prev": 0.22,
        "stage_weights": [0.10, 0.10, 0.15, 0.20, 0.45],
    },
    "aws": {
        "treat_offset": -0.5, "outcome_offset": -0.2, "never_smoker_prev": 0.20,
        "stage_weights": [0.12, 0.12, 0.16, 0.20, 0.40],
    },
    "azure": {
        "treat_offset": 0.0, "outcome_offset": 0.0, "never_smoker_prev": 0.30,
        "stage_weights": [0.10, 0.10, 0.15, 0.20, 0.45],
    },
    "gcp": {
        "treat_offset": 0.7, "outcome_offset": 0.3, "never_smoker_prev": 0.15,
        "stage_weights": [0.06, 0.08, 0.14, 0.22, 0.50],
    },
}

# Log-odds coefficients. Age terms are per year centred at 65.
LUNG_DGP = {
    "egfr": {"intercept": -1.3, "never_smoker": 1.7, "female": 0.4, "age_per_year": -0.01},
    "ecog_ge2": {"intercept": -1.4, "age_per_year": 0.03, "stage_iv": 0.6, "frailty": 1.0},
    "albumin": {"mean": 4.0, "sd": 0.4, "ecog_ge2": -0.3, "stage_iv": -0.2, "frailty": -0.3},
    "treatment": {"intercept": -1.6, "egfr": 3.2, "age_per_year": -0.02, "ecog_ge2": 0.4, "stage_iv": 0.3},
    "outcome": {
        "intercept": -0.4, "tau_egfr_neg": 0.2, "tau_egfr_pos": -1.4,
        "stage_iv": 0.6, "ecog_ge2": 0.5, "albumin_z": -0.3, "age_per_year": 0.03,
    },
    "death_given_progression": 0.35,
}

# Strength (kappa) of a latent frailty variable entering both the treatment and
# outcome models. "none" gives conditional exchangeability given measured covariates.
UNMEASURED_CONFOUNDING = {"none": 0.0, "mild": 0.5, "strong": 1.0}

CANCER_TYPE = "lung"
LUNG = {
    "condition": {
        "snomed": ("254637007", "Non-small cell lung cancer"),
        "icd10": ("C34.90", "Malignant neoplasm of unspecified part of unspecified bronchus or lung"),
    },
    # (loinc, display, unit, low, high)
    "tumor_markers": [
        ("2039-6", "Carcinoembryonic Ag [Mass/volume] in Serum or Plasma", "ng/mL", 1.0, 80.0),
        ("33717-0", "Cytokeratin 19 fragment [Mass/volume] in Serum", "ng/mL", 0.8, 15.0),
    ],
    "egfr_test": ("21667-1", "EGFR gene mutations found [Identifier] in Tissue by Molecular genetics method"),
}

# Common vital-sign / lab Observations applicable to all patients.
COMMON_OBSERVATIONS = [
    # (loinc, display, unit, low, high, category)
    ("8302-2", "Body height", "cm", 150.0, 195.0, "vital-signs"),
    ("29463-7", "Body weight", "kg", 48.0, 110.0, "vital-signs"),
    ("8867-4", "Heart rate", "/min", 55.0, 105.0, "vital-signs"),
    ("8480-6", "Systolic blood pressure", "mm[Hg]", 95.0, 165.0, "vital-signs"),
    ("8462-4", "Diastolic blood pressure", "mm[Hg]", 55.0, 100.0, "vital-signs"),
    ("718-7", "Hemoglobin [Mass/volume] in Blood", "g/dL", 8.5, 16.5, "laboratory"),
    ("6690-2", "Leukocytes [#/volume] in Blood by Automated count", "10*3/uL", 2.5, 12.0, "laboratory"),
    ("777-3", "Platelets [#/volume] in Blood by Automated count", "10*3/uL", 90.0, 420.0, "laboratory"),
    ("2160-0", "Creatinine [Mass/volume] in Serum or Plasma", "mg/dL", 0.5, 1.6, "laboratory"),
    ("1751-7", "Albumin [Mass/volume] in Serum or Plasma", "g/dL", 2.8, 5.0, "laboratory"),
]

FIRST_NAMES = {
    "male": ["James", "Oliver", "Noah", "Arthur", "George", "Leo", "Mohammed", "Harry", "Daniel", "Ethan"],
    "female": ["Olivia", "Amelia", "Isla", "Ava", "Freya", "Grace", "Sofia", "Emily", "Aisha", "Chloe"],
}
LAST_NAMES = [
    "Smith", "Jones", "Taylor", "Brown", "Williams", "Wilson", "Johnson",
    "Davies", "Patel", "Robinson", "Wright", "Khan", "Lewis", "Walker", "Hall",
]

TNM_STAGES = ["Stage I", "Stage II", "Stage IIIA", "Stage IIIB", "Stage IV"]
STAGE_SNOMED = {
    "Stage I": ("258215001", "Stage 1"),
    "Stage II": ("258219007", "Stage 2"),
    "Stage IIIA": ("261638004", "Stage 3A"),
    "Stage IIIB": ("261639007", "Stage 3B"),
    "Stage IV": ("258228008", "Stage 4"),
}


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def new_id() -> str:
    return str(uuid.uuid4())


def iso(dt: datetime) -> str:
    return dt.replace(microsecond=0, tzinfo=timezone.utc).isoformat()


def codeable_concept(system: str, code: str, display: str) -> dict:
    return {"coding": [{"system": system, "code": code, "display": display}], "text": display}


def random_datetime_between(start: datetime, end: datetime) -> datetime:
    delta = end - start
    return start + timedelta(seconds=random.randint(0, int(delta.total_seconds())))


def logistic(x: float) -> float:
    return 1.0 / (1.0 + math.exp(-x))


def bernoulli(p: float) -> bool:
    return random.random() < p


# ---------------------------------------------------------------------------
# Resource builders
# ---------------------------------------------------------------------------

def build_patient(site: str = "default") -> dict:
    gender = random.choice(["male", "female"])
    first = random.choice(FIRST_NAMES[gender])
    last = random.choice(LAST_NAMES)
    birth_year = random.randint(1940, 1985)
    birth = date(birth_year, random.randint(1, 12), random.randint(1, 28))
    pid = new_id()
    return {
        "resourceType": "Patient",
        "id": pid,
        "meta": {"profile": ["http://hl7.org/fhir/StructureDefinition/Patient"]},
        "identifier": [
            {
                "system": "urn:synthetic:mrn",
                "value": f"MRN-{random.randint(100000, 999999)}",
            }
        ],
        "active": True,
        "name": [{"use": "official", "family": last, "given": [first]}],
        "gender": gender,
        "birthDate": birth.isoformat(),
        "managingOrganization": {"reference": f"Organization/{site}", "display": site},
    }


def build_condition(patient_id: str, onset: datetime, stage: str) -> dict:
    spec = LUNG["condition"]
    snomed_code, snomed_display = spec["snomed"]
    icd_code, icd_display = spec["icd10"]
    stage_code, stage_display = STAGE_SNOMED[stage]
    return {
        "resourceType": "Condition",
        "id": new_id(),
        "meta": {"profile": ["http://hl7.org/fhir/StructureDefinition/Condition"]},
        "clinicalStatus": {
            "coding": [
                {
                    "system": "http://terminology.hl7.org/CodeSystem/condition-clinical",
                    "code": "active",
                    "display": "Active",
                }
            ]
        },
        "verificationStatus": {
            "coding": [
                {
                    "system": "http://terminology.hl7.org/CodeSystem/condition-ver-status",
                    "code": "confirmed",
                    "display": "Confirmed",
                }
            ]
        },
        "category": [
            {
                "coding": [
                    {
                        "system": "http://terminology.hl7.org/CodeSystem/condition-category",
                        "code": "problem-list-item",
                        "display": "Problem List Item",
                    }
                ]
            }
        ],
        "code": {
            "coding": [
                {"system": SNOMED, "code": snomed_code, "display": snomed_display},
                {"system": ICD10, "code": icd_code, "display": icd_display},
            ],
            "text": snomed_display,
        },
        "subject": {"reference": f"Patient/{patient_id}"},
        "onsetDateTime": iso(onset),
        "recordedDate": iso(onset + timedelta(days=random.randint(1, 14))),
        "stage": [
            {
                "summary": codeable_concept(SNOMED, stage_code, stage_display),
                "type": codeable_concept(SNOMED, "260998006", "Clinical staging (qualifier value)"),
            }
        ],
    }


def build_quantity_observation(
    patient_id: str,
    loinc: str,
    display: str,
    unit: str,
    low: float,
    high: float,
    effective: datetime,
    category: str = "laboratory",
    value: float | None = None,
) -> dict:
    if value is None:
        value = round(random.uniform(low, high), 1)
    return {
        "resourceType": "Observation",
        "id": new_id(),
        "meta": {"profile": ["http://hl7.org/fhir/StructureDefinition/Observation"]},
        "status": "final",
        "category": [
            {
                "coding": [
                    {
                        "system": "http://terminology.hl7.org/CodeSystem/observation-category",
                        "code": category,
                        "display": category.replace("-", " ").title(),
                    }
                ]
            }
        ],
        "code": codeable_concept(LOINC, loinc, display),
        "subject": {"reference": f"Patient/{patient_id}"},
        "effectiveDateTime": iso(effective),
        "valueQuantity": {"value": value, "unit": unit, "system": UCUM, "code": unit},
    }


def build_coded_observation(
    patient_id: str,
    loinc: str,
    display: str,
    effective: datetime,
    value_system: str,
    value_code: str,
    value_text: str,
    category: str = "laboratory",
) -> dict:
    return {
        "resourceType": "Observation",
        "id": new_id(),
        "meta": {"profile": ["http://hl7.org/fhir/StructureDefinition/Observation"]},
        "status": "final",
        "category": [
            {
                "coding": [
                    {
                        "system": "http://terminology.hl7.org/CodeSystem/observation-category",
                        "code": category,
                        "display": category.replace("-", " ").title(),
                    }
                ]
            }
        ],
        "code": codeable_concept(LOINC, loinc, display),
        "subject": {"reference": f"Patient/{patient_id}"},
        "effectiveDateTime": iso(effective),
        "valueCodeableConcept": codeable_concept(value_system, value_code, value_text),
    }


def build_interpretation_observation(
    patient_id: str, loinc: str, display: str, effective: datetime, positive: bool | None = None
) -> dict:
    if positive is None:
        positive = random.random() < 0.5
    code, text = ("10828004", "Positive") if positive else ("260385009", "Negative")
    return build_coded_observation(patient_id, loinc, display, effective, SNOMED, code, text)


def build_integer_observation(
    patient_id: str, loinc: str, display: str, effective: datetime, value: int, category: str = "survey"
) -> dict:
    return {
        "resourceType": "Observation",
        "id": new_id(),
        "meta": {"profile": ["http://hl7.org/fhir/StructureDefinition/Observation"]},
        "status": "final",
        "category": [
            {
                "coding": [
                    {
                        "system": "http://terminology.hl7.org/CodeSystem/observation-category",
                        "code": category,
                        "display": category.replace("-", " ").title(),
                    }
                ]
            }
        ],
        "code": codeable_concept(LOINC, loinc, display),
        "subject": {"reference": f"Patient/{patient_id}"},
        "effectiveDateTime": iso(effective),
        "valueInteger": value,
    }


def build_medication_request(
    patient_id: str,
    condition_id: str,
    rxnorm: str,
    display: str,
    authored: datetime,
    status: str | None = None,
    period_days: int | None = None,
) -> dict:
    return {
        "resourceType": "MedicationRequest",
        "id": new_id(),
        "meta": {"profile": ["http://hl7.org/fhir/StructureDefinition/MedicationRequest"]},
        "status": status or random.choice(["active", "completed"]),
        "intent": "order",
        "medicationCodeableConcept": codeable_concept(RXNORM, rxnorm, display),
        "subject": {"reference": f"Patient/{patient_id}"},
        "authoredOn": iso(authored),
        "reasonReference": [{"reference": f"Condition/{condition_id}"}],
        "dosageInstruction": [
            {
                "text": "Administer per oncology protocol",
                "timing": {
                    "repeat": {
                        "frequency": 1,
                        "period": period_days or random.choice([1, 7, 21]),
                        "periodUnit": "d",
                    }
                },
            }
        ],
    }


# ---------------------------------------------------------------------------
# Patient record assembly
# ---------------------------------------------------------------------------

def _bundle(resources: list[dict]) -> dict:
    return {
        "resourceType": "Bundle",
        "id": new_id(),
        "type": "collection",
        "timestamp": iso(datetime.now(timezone.utc).replace(tzinfo=None)),
        "entry": [
            {"fullUrl": f"urn:uuid:{r['id']}", "resource": r} for r in resources
        ],
    }


def _encounter_observations(
    pid: str,
    effective: datetime,
    exclude_loinc: set[str] = frozenset(),
) -> list[dict]:
    """Random vitals/labs plus serum tumour markers for one encounter."""
    out: list[dict] = []
    pool = [o for o in COMMON_OBSERVATIONS if o[0] not in exclude_loinc]
    sampled = random.sample(pool, k=min(random.randint(4, 7), len(pool)))
    for loinc, display, unit, low, high, category in sampled:
        out.append(build_quantity_observation(pid, loinc, display, unit, low, high, effective, category))
    for loinc, display, unit, low, high in LUNG["tumor_markers"]:
        out.append(build_quantity_observation(pid, loinc, display, unit, low, high, effective))
    return out


def simulate_lung_covariates_and_outcomes(
    age: float, female: bool, site: str, kappa: float
) -> dict:
    """Draw covariates, treatment and potential outcomes from the lung structural model."""
    cfg = SITES[site]
    d = LUNG_DGP
    age_c = age - 65.0

    r = random.random()
    if r < cfg["never_smoker_prev"]:
        smoking = "never"
    elif r < cfg["never_smoker_prev"] + 0.45:
        smoking = "former"
    else:
        smoking = "current"

    stage = random.choices(TNM_STAGES, weights=cfg["stage_weights"])[0]
    stage_iv = stage == "Stage IV"
    frailty = random.gauss(0.0, 1.0)

    e = d["egfr"]
    egfr = bernoulli(logistic(
        e["intercept"] + e["never_smoker"] * (smoking == "never") + e["female"] * female + e["age_per_year"] * age_c
    ))

    c = d["ecog_ge2"]
    ecog_ge2 = bernoulli(logistic(
        c["intercept"] + c["age_per_year"] * age_c + c["stage_iv"] * stage_iv + c["frailty"] * frailty
    ))
    ecog = random.choice([2, 3]) if ecog_ge2 else random.choice([0, 1])

    a = d["albumin"]
    albumin_mean = a["mean"] + a["ecog_ge2"] * ecog_ge2 + a["stage_iv"] * stage_iv + a["frailty"] * frailty
    albumin = round(min(5.0, max(2.5, random.gauss(albumin_mean, a["sd"]))), 1)
    albumin_z = (albumin - a["mean"]) / a["sd"]

    t = d["treatment"]
    propensity = logistic(
        t["intercept"] + cfg["treat_offset"] + t["egfr"] * egfr + t["age_per_year"] * age_c
        + t["ecog_ge2"] * ecog_ge2 + t["stage_iv"] * stage_iv + kappa * frailty
    )
    treated = bernoulli(propensity)

    o = d["outcome"]
    base = (
        o["intercept"] + cfg["outcome_offset"] + o["stage_iv"] * stage_iv + o["ecog_ge2"] * ecog_ge2
        + o["albumin_z"] * albumin_z + o["age_per_year"] * age_c + kappa * frailty
    )
    tau = o["tau_egfr_pos"] if egfr else o["tau_egfr_neg"]
    p0 = logistic(base)
    p1 = logistic(base + tau)
    u = random.random()
    y0 = u < p0
    y1 = u < p1
    y = y1 if treated else y0

    return {
        "smoking_status": smoking,
        "stage": stage,
        "frailty": round(frailty, 4),
        "egfr_positive": int(egfr),
        "ecog": ecog,
        "albumin": albumin,
        "propensity": round(propensity, 4),
        "treatment": "osimertinib" if treated else "chemo",
        "p0": round(p0, 4),
        "p1": round(p1, 4),
        "y0": int(y0),
        "y1": int(y1),
        "y_obs": int(y),
    }


def generate_patient_record(site: str = "default", confounding: str = "none") -> tuple[dict, dict]:
    """NSCLC patient with confounded treatment and a known-outcome model.

    Returns (FHIR Bundle, ground-truth row).
    """
    kappa = UNMEASURED_CONFOUNDING[confounding]
    patient = build_patient(site)
    pid = patient["id"]

    now = datetime.now(timezone.utc).replace(tzinfo=None)
    # Ensure >= 12 months follow-up plus room for a death event after progression.
    diagnosis = random_datetime_between(now - timedelta(days=3 * 365), now - timedelta(days=550))
    birth = date.fromisoformat(patient["birthDate"])
    age = (diagnosis.date() - birth).days / 365.25

    sim = simulate_lung_covariates_and_outcomes(age, patient["gender"] == "female", site, kappa)

    condition = build_condition(pid, diagnosis, sim["stage"])
    resources: list[dict] = [patient, condition]

    # Baseline encounter (pre-treatment): fixed EGFR, ECOG, smoking, albumin.
    baseline = diagnosis + timedelta(days=random.randint(1, 10))
    resources.append(
        build_interpretation_observation(pid, *LUNG["egfr_test"], baseline, positive=bool(sim["egfr_positive"]))
    )
    resources.append(build_integer_observation(pid, *LOINC_ECOG, baseline, sim["ecog"]))
    smk_code, smk_text = SMOKING_SNOMED[sim["smoking_status"]]
    resources.append(
        build_coded_observation(pid, *LOINC_SMOKING, baseline, SNOMED, smk_code, smk_text, category="social-history")
    )
    alb = next(o for o in COMMON_OBSERVATIONS if o[0] == "1751-7")
    resources.append(
        build_quantity_observation(pid, alb[0], alb[1], alb[2], alb[3], alb[4], baseline, alb[5], value=sim["albumin"])
    )
    resources.extend(_encounter_observations(pid, baseline, exclude_loinc={"1751-7"}))

    # First-line treatment.
    treatment_start = diagnosis + timedelta(days=random.randint(14, 45))
    med_status = "completed" if sim["y_obs"] else "active"
    if sim["treatment"] == "osimertinib":
        regimen = [(OSIMERTINIB, 1)]
    else:
        regimen = [(CARBOPLATIN, 21), (random.choice([PEMETREXED, PACLITAXEL]), 21)]
        if random.random() < 0.4:
            regimen.append((PEMBROLIZUMAB, 21))
    for (rxnorm, display), period in regimen:
        resources.append(
            build_medication_request(pid, condition["id"], rxnorm, display, treatment_start, med_status, period)
        )

    # Follow-up encounters (post-treatment; random labs, no repeat EGFR test).
    for _ in range(random.randint(1, 3)):
        effective = random_datetime_between(treatment_start, now)
        resources.extend(_encounter_observations(pid, effective))

    # Outcome: progression within 12 months, else stable disease at 12 months.
    progression_days = None
    died = 0
    if sim["y_obs"]:
        progression_days = random.randint(30, 365)
        prog_dt = treatment_start + timedelta(days=progression_days)
        code, text = RESPONSE_LOINC_ANSWERS["progressive"]
        resources.append(build_coded_observation(pid, *LOINC_RESPONSE, prog_dt, LOINC, code, text, category="exam"))
        if bernoulli(LUNG_DGP["death_given_progression"]):
            died = 1
            patient["deceasedDateTime"] = iso(prog_dt + timedelta(days=random.randint(0, 120)))
    else:
        code, text = RESPONSE_LOINC_ANSWERS["stable"]
        resources.append(
            build_coded_observation(
                pid, *LOINC_RESPONSE, treatment_start + timedelta(days=365), LOINC, code, text, category="exam"
            )
        )

    truth = {
        "patient_id": pid,
        "site": site,
        "age": round(age, 1),
        "gender": patient["gender"],
        "treatment_start": iso(treatment_start),
        **sim,
        "progression_days": progression_days if progression_days is not None else "",
        "died": died,
    }
    return _bundle(resources), truth


# ---------------------------------------------------------------------------
# Flattening FHIR resources into table rows
# ---------------------------------------------------------------------------

def _coding(resource_field: dict, system: str) -> tuple[str, str]:
    """Return (code, display) for the coding with the given system, or ('', '')."""
    for coding in resource_field.get("coding", []):
        if coding.get("system") == system:
            return coding.get("code", ""), coding.get("display", "")
    return "", ""


def flatten_patient(r: dict) -> dict:
    name = r["name"][0]
    return {
        "patient_id": r["id"],
        "mrn": r["identifier"][0]["value"],
        "family_name": name["family"],
        "given_name": name["given"][0],
        "gender": r["gender"],
        "birth_date": r["birthDate"],
        "deceased_datetime": r.get("deceasedDateTime", ""),
        "managing_organization": r.get("managingOrganization", {}).get("display", ""),
        "cancer_type": CANCER_TYPE,
    }


def flatten_condition(r: dict) -> dict:
    snomed_code, snomed_display = _coding(r["code"], SNOMED)
    icd_code, _ = _coding(r["code"], ICD10)
    stage = r["stage"][0]["summary"]["coding"][0]
    return {
        "condition_id": r["id"],
        "patient_id": r["subject"]["reference"].split("/")[1],
        "snomed_code": snomed_code,
        "icd10_code": icd_code,
        "description": snomed_display,
        "clinical_status": r["clinicalStatus"]["coding"][0]["code"],
        "verification_status": r["verificationStatus"]["coding"][0]["code"],
        "stage": stage["display"],
        "onset_datetime": r["onsetDateTime"],
        "recorded_date": r["recordedDate"],
    }


def flatten_observation(r: dict) -> dict:
    loinc_code, loinc_display = _coding(r["code"], LOINC)
    row = {
        "observation_id": r["id"],
        "patient_id": r["subject"]["reference"].split("/")[1],
        "loinc_code": loinc_code,
        "description": loinc_display,
        "category": r["category"][0]["coding"][0]["code"],
        "effective_datetime": r["effectiveDateTime"],
        "value": "",
        "unit": "",
        "value_text": "",
    }
    if "valueQuantity" in r:
        row["value"] = r["valueQuantity"]["value"]
        row["unit"] = r["valueQuantity"]["unit"]
    elif "valueInteger" in r:
        row["value"] = r["valueInteger"]
    elif "valueCodeableConcept" in r:
        row["value_text"] = r["valueCodeableConcept"]["text"]
    return row


def flatten_medication_request(r: dict) -> dict:
    rx_code, rx_display = _coding(r["medicationCodeableConcept"], RXNORM)
    timing = r["dosageInstruction"][0]["timing"]["repeat"]
    return {
        "medication_request_id": r["id"],
        "patient_id": r["subject"]["reference"].split("/")[1],
        "condition_id": r["reasonReference"][0]["reference"].split("/")[1],
        "rxnorm_code": rx_code,
        "medication": rx_display,
        "status": r["status"],
        "intent": r["intent"],
        "authored_on": r["authoredOn"],
        "dosage_text": r["dosageInstruction"][0]["text"],
        "frequency": timing["frequency"],
        "period_days": timing["period"],
    }


datetype_conversion = ['birth_date', 'deceased_datetime','onset_datetime','recorded_date','effective_datetime','authored_on']

inttype_conversions = ['egfr_positive','ecog','died']

floattype_conversions = ['age','value','frailty','albumin','frequency','period_days','propensity','p0','p1','y0','y1','y_obs','progression_days']


def csv_to_dataframe(rows: list[dict]) -> pd.DataFrame:
    df = pd.DataFrame(rows)
    for col in df:
        if col in datetype_conversion:
            df[col] = pd.to_datetime(df[col])
        if col in inttype_conversions:
            df[col] = df[col].astype(int)
        if col in floattype_conversions:
            df[col] = df[col].map(lambda x: float(x) if x is not '' else None)
    return df




def write_table(path: Path, rows: list[dict]) -> None:
    print(f"writing out {path}")
    if not rows:
        return
    df = csv_to_dataframe(rows)
    fp.write(path,df)
    # with path.open("w", newline="") as f:
    #     writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    #     writer.writeheader()
    #     writer.writerows(rows)
    print(f"Wrote {path}  ({len(rows)} rows)")


def true_effects(truth_rows: list[dict]) -> dict:
    """Sample-average true risk differences E[p1 - p0] overall and by EGFR status."""
    def ate(rows: list[dict]) -> float | None:
        return round(sum(r["p1"] - r["p0"] for r in rows) / len(rows), 4) if rows else None

    return {
        "ate_all": ate(truth_rows),
        "ate_egfr_positive": ate([r for r in truth_rows if r["egfr_positive"]]),
        "ate_egfr_negative": ate([r for r in truth_rows if not r["egfr_positive"]]),
        "ate_advanced_egfr_positive": ate(
            [r for r in truth_rows if r["egfr_positive"] and r["stage"] in ("Stage IIIB", "Stage IV")]
        ),
        "n": len(truth_rows),
        "treated_fraction": round(sum(r["treatment"] == "osimertinib" for r in truth_rows) / len(truth_rows), 4),
        "progression_fraction": round(sum(r["y_obs"] for r in truth_rows) / len(truth_rows), 4),
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Generate synthetic FHIR NSCLC EHR data.")
    parser.add_argument("--patients", type=int, default=20, help="Number of patients (default 20)")
    parser.add_argument("--output", type=Path, default=Path("output"), help="Output directory")
    parser.add_argument("--seed", type=int, default=None, help="Random seed for reproducibility")
    parser.add_argument(
        "--site", choices=sorted(SITES), default="default",
        help="Hospital site profile (prescribing culture, baseline risk, case-mix)",
    )
    parser.add_argument(
        "--unmeasured-confounding", choices=sorted(UNMEASURED_CONFOUNDING), default="none",
        help="Strength of a latent frailty confounder in the structural model",
    )
    args = parser.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    args.output.mkdir(parents=True, exist_ok=True)

    patients, conditions, observations, medication_requests, truth_rows = [], [], [], [], []

    for _ in range(args.patients):
        bundle, truth = generate_patient_record(args.site, args.unmeasured_confounding)
        truth_rows.append(truth)
        for entry in bundle["entry"]:
            r = entry["resource"]
            rt = r["resourceType"]
            if rt == "Patient":
                patients.append(flatten_patient(r))
            elif rt == "Condition":
                conditions.append(flatten_condition(r))
            elif rt == "Observation":
                observations.append(flatten_observation(r))
            elif rt == "MedicationRequest":
                medication_requests.append(flatten_medication_request(r))

    write_table(args.output / "patients.parq", patients)
    write_table(args.output / "conditions.parq", conditions)
    write_table(args.output / "observations.parq", observations)
    write_table(args.output / "medication_requests.parq", medication_requests)

    write_table(args.output / "ground_truth.parq", truth_rows)
    meta = {
        "site": args.site,
        "site_config": SITES[args.site],
        "unmeasured_confounding": args.unmeasured_confounding,
        "kappa": UNMEASURED_CONFOUNDING[args.unmeasured_confounding],
        "seed": args.seed,
        "dgp": LUNG_DGP,
        "true_effects": true_effects(truth_rows),
    }
    (args.output / "ground_truth.json").write_text(json.dumps(meta, indent=2))
    print(f"Wrote {args.output / 'ground_truth.json'}  {meta['true_effects']}")


if __name__ == "__main__":
    main()
