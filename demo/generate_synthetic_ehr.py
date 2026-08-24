"""
Synthetic FHIR R4 EHR data generator for cancer patients.

Generates FHIR-compliant resources per https://hl7.org/fhir/ :
  - Patient
  - Condition        (problems  -> Condition resource, ICD-10 + SNOMED CT codings)
  - Observation      (vitals, labs, tumour markers   -> LOINC codings)
  - MedicationRequest (medications -> RxNorm codings)

Cancer types covered: penile, ovarian, breast, lung.

Output: structured CSV tables in the output directory, one per resource
type: patients.csv, conditions.csv, observations.csv,
medication_requests.csv. Tables are linked via patient_id / condition_id.

Usage:
    python generate_synthetic_ehr.py --patients 20 --output ./output --seed 42
"""

from __future__ import annotations

import argparse
import csv
import random
import uuid
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

# ---------------------------------------------------------------------------
# Reference terminology
# ---------------------------------------------------------------------------

SNOMED = "http://snomed.info/sct"
ICD10 = "http://hl7.org/fhir/sid/icd-10"
LOINC = "http://loinc.org"
RXNORM = "http://www.nlm.nih.gov/research/umls/rxnorm"
UCUM = "http://unitsofmeasure.org"

CANCER_TYPES = {
    "penile": {
        "gender": "male",
        "condition": {
            "snomed": ("363515000", "Malignant neoplasm of penis"),
            "icd10": ("C60.9", "Malignant neoplasm of penis, unspecified"),
        },
        "tumor_markers": [
            # (loinc, display, unit, low, high)
            ("31146-4", "Squamous cell carcinoma antigen [Mass/volume] in Serum", "ng/mL", 0.5, 12.0),
        ],
        "medications": [
            ("1736854", "cisplatin 1 MG/ML Injectable Solution"),
            ("6851", "fluorouracil"),
            ("589511", "paclitaxel 6 MG/ML Injectable Solution"),
            ("40048", "ifosfamide"),
        ],
    },
    "ovarian": {
        "gender": "female",
        "condition": {
            "snomed": ("363443007", "Malignant tumor of ovary"),
            "icd10": ("C56.9", "Malignant neoplasm of unspecified ovary"),
        },
        "tumor_markers": [
            ("10334-1", "Cancer Ag 125 [Units/volume] in Serum or Plasma", "U/mL", 20.0, 1200.0),
            ("2039-6", "Carcinoembryonic Ag [Mass/volume] in Serum or Plasma", "ng/mL", 0.5, 20.0),
        ],
        "medications": [
            ("40048", "carboplatin"),
            ("589511", "paclitaxel 6 MG/ML Injectable Solution"),
            ("1946828", "niraparib 100 MG Oral Capsule"),
            ("1147220", "bevacizumab 25 MG/ML Injectable Solution"),
            ("1731324", "olaparib 100 MG Oral Tablet"),
        ],
    },
    "breast": {
        "gender": "female",
        "condition": {
            "snomed": ("254837009", "Malignant neoplasm of breast"),
            "icd10": ("C50.9", "Malignant neoplasm of breast of unspecified site"),
        },
        "tumor_markers": [
            ("6875-9", "Cancer Ag 15-3 [Units/volume] in Serum or Plasma", "U/mL", 10.0, 150.0),
            ("2039-6", "Carcinoembryonic Ag [Mass/volume] in Serum or Plasma", "ng/mL", 0.5, 25.0),
            ("48676-1", "HER2 [Interpretation] in Tissue", None, None, None),
            ("16112-5", "Estrogen receptor [Interpretation] in Tissue", None, None, None),
        ],
        "medications": [
            ("10324", "tamoxifen"),
            ("224905", "trastuzumab"),
            ("589511", "paclitaxel 6 MG/ML Injectable Solution"),
            ("3639", "doxorubicin"),
            ("1156181", "anastrozole 1 MG Oral Tablet"),
            ("1946840", "palbociclib 125 MG Oral Capsule"),
        ],
    },
    "lung": {
        "gender": None,  # either
        "condition": {
            "snomed": ("254637007", "Non-small cell lung cancer"),
            "icd10": ("C34.90", "Malignant neoplasm of unspecified part of unspecified bronchus or lung"),
        },
        "tumor_markers": [
            ("2039-6", "Carcinoembryonic Ag [Mass/volume] in Serum or Plasma", "ng/mL", 1.0, 80.0),
            ("33717-0", "Cytokeratin 19 fragment [Mass/volume] in Serum", "ng/mL", 0.8, 15.0),
            ("21667-1", "EGFR gene mutations found [Identifier] in Tissue by Molecular genetics method", None, None, None),
        ],
        "medications": [
            ("1547220", "pembrolizumab 25 MG/ML Injectable Solution"),
            ("1721560", "osimertinib 80 MG Oral Tablet"),
            ("1094833", "erlotinib 150 MG Oral Tablet"),
            ("589511", "paclitaxel 6 MG/ML Injectable Solution"),
            ("40048", "carboplatin"),
            ("477467", "pemetrexed"),
        ],
    },
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


# ---------------------------------------------------------------------------
# Resource builders
# ---------------------------------------------------------------------------

def build_patient(cancer_type: str) -> dict:
    spec = CANCER_TYPES[cancer_type]
    gender = spec["gender"] or random.choice(["male", "female"])
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
    }


def build_condition(patient_id: str, cancer_type: str, onset: datetime) -> dict:
    spec = CANCER_TYPES[cancer_type]["condition"]
    snomed_code, snomed_display = spec["snomed"]
    icd_code, icd_display = spec["icd10"]
    stage = random.choice(TNM_STAGES)
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
) -> dict:
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


def build_interpretation_observation(
    patient_id: str, loinc: str, display: str, effective: datetime
) -> dict:
    positive = random.random() < 0.5
    code, text = ("10828004", "Positive") if positive else ("260385009", "Negative")
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
                        "code": "laboratory",
                        "display": "Laboratory",
                    }
                ]
            }
        ],
        "code": codeable_concept(LOINC, loinc, display),
        "subject": {"reference": f"Patient/{patient_id}"},
        "effectiveDateTime": iso(effective),
        "valueCodeableConcept": codeable_concept(SNOMED, code, text),
    }


def build_medication_request(
    patient_id: str,
    condition_id: str,
    rxnorm: str,
    display: str,
    authored: datetime,
) -> dict:
    return {
        "resourceType": "MedicationRequest",
        "id": new_id(),
        "meta": {"profile": ["http://hl7.org/fhir/StructureDefinition/MedicationRequest"]},
        "status": random.choice(["active", "completed"]),
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
                        "period": random.choice([1, 7, 21]),
                        "periodUnit": "d",
                    }
                },
            }
        ],
    }


# ---------------------------------------------------------------------------
# Patient record assembly
# ---------------------------------------------------------------------------

def generate_patient_record(cancer_type: str) -> dict:
    """Return a FHIR Bundle (collection) for one synthetic patient."""
    patient = build_patient(cancer_type)
    pid = patient["id"]

    now = datetime.now(timezone.utc).replace(tzinfo=None)
    diagnosis = random_datetime_between(now - timedelta(days=3 * 365), now - timedelta(days=90))

    condition = build_condition(pid, cancer_type, diagnosis)

    resources: list[dict] = [patient, condition]

    # Observations: 2-4 encounters after diagnosis, each with vitals/labs + tumour markers.
    spec = CANCER_TYPES[cancer_type]
    for _ in range(random.randint(2, 4)):
        effective = random_datetime_between(diagnosis, now)
        sampled = random.sample(COMMON_OBSERVATIONS, k=random.randint(4, 7))
        for loinc, display, unit, low, high, category in sampled:
            resources.append(
                build_quantity_observation(pid, loinc, display, unit, low, high, effective, category)
            )
        for loinc, display, unit, low, high in spec["tumor_markers"]:
            if unit is None:
                resources.append(build_interpretation_observation(pid, loinc, display, effective))
            else:
                resources.append(
                    build_quantity_observation(pid, loinc, display, unit, low, high, effective)
                )

    # Medications: 2-4 drugs from the regimen list for this cancer type.
    meds = random.sample(spec["medications"], k=min(random.randint(2, 4), len(spec["medications"])))
    for rxnorm, display in meds:
        authored = random_datetime_between(diagnosis, now)
        resources.append(build_medication_request(pid, condition["id"], rxnorm, display, authored))

    return {
        "resourceType": "Bundle",
        "id": new_id(),
        "type": "collection",
        "timestamp": iso(datetime.now(timezone.utc).replace(tzinfo=None)),
        "entry": [
            {"fullUrl": f"urn:uuid:{r['id']}", "resource": r} for r in resources
        ],
    }


# ---------------------------------------------------------------------------
# Flattening FHIR resources into table rows
# ---------------------------------------------------------------------------

def _coding(resource_field: dict, system: str) -> tuple[str, str]:
    """Return (code, display) for the coding with the given system, or ('', '')."""
    for coding in resource_field.get("coding", []):
        if coding.get("system") == system:
            return coding.get("code", ""), coding.get("display", "")
    return "", ""


def flatten_patient(r: dict, cancer_type: str) -> dict:
    name = r["name"][0]
    return {
        "patient_id": r["id"],
        "mrn": r["identifier"][0]["value"],
        "family_name": name["family"],
        "given_name": name["given"][0],
        "gender": r["gender"],
        "birth_date": r["birthDate"],
        "cancer_type": cancer_type,
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


def write_table(path: Path, rows: list[dict]) -> None:
    with path.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {path}  ({len(rows)} rows)")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Generate synthetic FHIR cancer EHR data.")
    parser.add_argument("--patients", type=int, default=20, help="Total number of patients (default 20)")
    parser.add_argument("--output", type=Path, default=Path("output"), help="Output directory")
    parser.add_argument("--seed", type=int, default=None, help="Random seed for reproducibility")
    args = parser.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    args.output.mkdir(parents=True, exist_ok=True)

    cancer_types = list(CANCER_TYPES)
    patients, conditions, observations, medication_requests = [], [], [], []

    for i in range(args.patients):
        cancer_type = cancer_types[i % len(cancer_types)]
        bundle = generate_patient_record(cancer_type)
        for entry in bundle["entry"]:
            r = entry["resource"]
            rt = r["resourceType"]
            if rt == "Patient":
                patients.append(flatten_patient(r, cancer_type))
            elif rt == "Condition":
                conditions.append(flatten_condition(r))
            elif rt == "Observation":
                observations.append(flatten_observation(r))
            elif rt == "MedicationRequest":
                medication_requests.append(flatten_medication_request(r))

    write_table(args.output / "patients.csv", patients)
    write_table(args.output / "conditions.csv", conditions)
    write_table(args.output / "observations.csv", observations)
    write_table(args.output / "medication_requests.csv", medication_requests)


if __name__ == "__main__":
    main()
