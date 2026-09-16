# pTDP-43 DAB IHC Quantification Pipeline for QuPath 0.7.0

A machine-learning-based pipeline for automated quantification of phosphorylated TDP-43 (pTDP-43) pathology in DAB-stained human post-mortem brain tissue sections using QuPath 0.7.0.

Developed at the **Maurice Wohl Clinical Neuroscience Institute, King's College London** as part of the [My Name'5 Doddie Foundation](https://www.myname5doddie.co.uk/) funded project: *"Dissecting a TDP-43 knock-in allelic series to yield diverse MND drug targets"*.

---

## Overview

This pipeline addresses a core problem in pTDP-43 IHC quantification: **DAB chromogen artefacts and precipitates are routinely misclassified as genuine pathological inclusions**, inflating inclusion counts and introducing systematic bias. The pipeline uses a Gaussian Naive Bayes classifier trained on shape and colour features to discriminate real signal from artefacts, with an optional second-stage morphological subclassification.

The three-script pipeline produces:
- Inclusion counts and densities per annotation region
- Nuclei counts per annotation region (for inclusion:cell ratio)
- Optional morphological breakdown across up to 7 inclusion subtypes

---

## Scripts

| Script | Purpose | Run order |
|--------|---------|-----------|
| `01_nuclei_counter.groovy` | Counts haematoxylin-stained nuclei per annotation | First |
| `02_DAB_classifier.groovy` | Classifies real pTDP-43 inclusions vs DAB artefacts | Second |
| `03_morphology_classifier.groovy` | Subclassifies inclusions by morphological category | Third (optional) |

---

## Requirements

- **QuPath 0.7.0** — [download here](https://qupath.github.io/)
- Brightfield whole-slide images with **DAB/haematoxylin staining**
- Images imported into a **QuPath project** (required for file path resolution)
- A saved **DAB pixel classifier** (thresholder) in QuPath for initial object creation

---

## Installation

1. Clone or download this repository
2. Open QuPath 0.7.0
3. Open your QuPath project containing your slide images
4. Open the Script Editor: **Automate → Script editor**
5. Open each `.groovy` file via **File → Open** inside the script editor

---

## Quick Start

### First time (training required)

```
Step 1:  Draw annotation regions on tissue
Step 2:  Run DAB pixel classifier → Create Objects (split enabled)
Step 3:  Ctrl+S to save
Step 4:  Right-click annotations → Unlock
Step 5:  Run 01_nuclei_counter.groovy
Step 6:  Run 02_DAB_classifier.groovy  [RUN_MODE = "EXTRACT"]
Step 7:  Label detections: right-click → Set classification
           → "TDP43_inclusion" for real signal
           → "DAB_artefact" for false positives
           Aim for 80+ examples per class
Step 8:  Run 02_DAB_classifier.groovy  [RUN_MODE = "TRAIN_SAVE"]
Step 9:  Run 02_DAB_classifier.groovy  [RUN_MODE = "LOAD_APPLY"]
```

### Subsequent slides (classifier already trained)

```
Step 1:  Draw annotations → Create Objects → Ctrl+S
Step 2:  Unlock annotations
Step 3:  Run 01_nuclei_counter.groovy
Step 4:  Run 02_DAB_classifier.groovy  [RUN_MODE = "EXTRACT"]
Step 5:  Run 02_DAB_classifier.groovy  [RUN_MODE = "LOAD_APPLY"]
```

---

## Script 01 — Nuclei Counter

Counts haematoxylin-positive nuclei per annotation region, saves counts to `results/nuclei_counts.csv`, then automatically restores any existing DAB detections to the hierarchy so they are not overwritten.

### Key parameters (tune for your tissue)

```groovy
def NUCLEI_THRESHOLD = 0.10   // haematoxylin OD — lower = more nuclei detected
def NUCLEI_MIN_AREA  = 15.0   // µm² — minimum nucleus size
def NUCLEI_MAX_AREA  = 350.0  // µm² — maximum nucleus size
def NUCLEI_SIGMA     = 1.5    // smoothing in µm
def NUCLEI_BG_RADIUS = 8.0    // background subtraction radius in µm
```

**Important:** All annotation regions must be **unlocked** before running. Right-click each annotation → Unlock.

---

## Script 02 — DAB Classifier

A Gaussian Naive Bayes classifier that separates genuine pTDP-43 inclusions from DAB artefacts (precipitates, myelin staining, lipofuscin, tissue folds) using 17 features derived from object geometry and RGB colour values.

### Modes

| Mode | Description |
|------|-------------|
| `DIAGNOSE` | Prints all measurements on current detections — run first to verify |
| `EXTRACT` | Computes 17 shape and colour features on all detections |
| `TRAIN_SAVE` | Trains classifier from labelled objects, prints accuracy, saves to disk |
| `LOAD_APPLY` | Loads saved classifier, removes artefacts, exports counts + nuclei ratio |

### Features used for classification

**Shape features (from ROI geometry):**
- Area (µm²), Perimeter (µm), Circularity, Aspect ratio, Solidity, Max Feret diameter

**Colour features (from pixel intensities):**
- Red/Green/Blue channel means and standard deviations
- DAB proxy (Red − Blue), Colour range, Mean intensity, Blue fraction, Colour standard deviation

### Output — `results/pTDP43_counts.csv`

```
Image, Annotation, Area_mm2, Inclusions, Nuclei, Inclusions_per_nucleus, Density_per_mm2
```

### Training accuracy interpretation

| Accuracy | Interpretation |
|----------|---------------|
| > 85% | Good — proceed with confidence |
| 70–85% | Usable — consider labelling more examples |
| < 70% | Poor — label more clear-cut examples, especially of the minority class |

---

## Script 03 — Morphology Classifier (optional)

Subclassifies real pTDP-43 inclusions into up to 7 morphological categories. Designed for use with a neuropathologist. Trains only on classes with sufficient labelled examples — rare classes are skipped automatically.

### Supported morphology classes

| Class name | Inclusion type |
|------------|---------------|
| `NCI_skein` | Skein-like neuronal cytoplasmic inclusion |
| `NCI_spherical` | Compact/spherical neuronal cytoplasmic inclusion |
| `NCI_pre_inclusion` | Diffuse pre-inclusion / early pathology |
| `NII` | Neuronal intranuclear inclusion |
| `DN` | Dystrophic neurite |
| `GCI_oligo` | Glial cytoplasmic inclusion (oligodendrocyte) |
| `GCI_astrocytic` | Glial cytoplasmic inclusion (astrocyte) |

You do not need to label all 7 classes. Any class with fewer than 5 labelled examples is automatically excluded from training. The pipeline is functional with as few as 2 classes.

### Modes

| Mode | Description |
|------|-------------|
| `LABEL_CHECK` | Shows labelling progress per class — run during labelling sessions |
| `TRAIN_SAVE` | Trains multiclass classifier, prints confusion matrix, saves to disk |
| `LOAD_APPLY` | Applies morphology classes to all inclusions, exports per-class counts |

### Output — `results/morphology_counts.csv`

```
Image, Annotation, Area_mm2, NCI_skein, NCI_spherical, NCI_pre_inclusion, NII, DN, GCI_oligo, GCI_astrocytic, Total_inclusions, Density_per_mm2
```

---

## Output files

All results are written to the `results/` folder inside your QuPath project directory.

| File | Contents |
|------|----------|
| `nuclei_counts.csv` | Nuclei per annotation region |
| `pTDP43_counts.csv` | Inclusions, nuclei, ratio, density per annotation |
| `morphology_counts.csv` | Per-class inclusion counts per annotation |
| `pTDP43_classifier.txt` | Saved binary classifier (real vs artefact) |
| `pTDP43_morphology_classifier.txt` | Saved morphology classifier |

To combine results across all slides, use the `Image` and `Annotation` columns as a composite key in Excel, R, or Python.

---

## Combining results in Excel

1. Open `nuclei_counts.csv` and `pTDP43_counts.csv` in Excel
2. In a new sheet, use `VLOOKUP` or `INDEX/MATCH` on concatenated `Image` + `Annotation` columns
3. Add calculated columns:
   - `Inclusions_per_nucleus = Inclusions / Nuclei`
   - Morphology percentages: `=NCI_skein / Total_inclusions`

---

## Citation

If you use this pipeline in your research, please get in touch — details in the Contact section below.

---

## Troubleshooting

**0 nuclei detected**
→ Lower `NUCLEI_THRESHOLD` in Script 01. Check annotations are unlocked. Confirm haematoxylin staining is present in your tissue.

**Script says "no feat: measurements"**
→ Run `EXTRACT` mode in Script 02 before `LOAD_APPLY`.

**Classifier calls everything artefact**
→ Class imbalance during training. Label more inclusion examples to balance your training set.

**"No annotations found for training"**
→ You are trying to use the QuPath GUI classifier dialog — this pipeline bypasses the GUI. Use the script modes only.

**Annotations locked error**
→ Right-click each annotation in the Annotations panel → Unlock, then re-run.

---

## Licence

MIT Licence — free to use, modify, and distribute with attribution.

---

## Contact

Matvey Evtushenko — matvey.evtushenko@kcl.ac.uk | [LinkedIn](https://linkedin.com/in/matvey-evtushenko) | Maurice Wohl Clinical Neuroscience Institute, King's College London
