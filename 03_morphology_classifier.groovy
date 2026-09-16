/**
 * pTDP-43 Morphology Classifier — QuPath 0.7.0
 * ================================================
 * Stage 2 classifier — runs AFTER pTDP43_DAB_classifier.groovy
 * Subclassifies real pTDP-43 inclusions into morphological categories.
 *
 * SEVEN MORPHOLOGY CLASSES (use only the ones present in your tissue):
 *   NCI_skein          - Skein-like neuronal cytoplasmic inclusion
 *   NCI_spherical      - Compact/spherical neuronal cytoplasmic inclusion
 *   NCI_pre_inclusion  - Diffuse pre-inclusion / early pathology
 *   NII                - Neuronal intranuclear inclusion
 *   DN                 - Dystrophic neurite
 *   GCI_oligo          - Glial cytoplasmic inclusion (oligodendrocyte)
 *   GCI_astrocytic     - Glial cytoplasmic inclusion (astrocyte)
 *
 * MODES:
 *   "LABEL_CHECK"  - Prints how many objects of each class you have labelled.
 *                    Run this while labelling to track your progress.
 *   "TRAIN_SAVE"   - Trains classifier on your labelled objects.
 *                    Skips any class with fewer than MIN_EXAMPLES examples.
 *                    Saves classifier to pTDP43_morphology_classifier.txt
 *   "LOAD_APPLY"   - Loads saved classifier, applies morphology classes to
 *                    all TDP43_inclusion objects, exports per-class counts
 *                    per annotation to morphology_counts.csv
 *
 * FULL WORKFLOW (all 3 scripts):
 *   See README at bottom of this file.
 *
 * IMPORTANT: Run pTDP43_DAB_classifier.groovy (LOAD_APPLY) BEFORE this script.
 * Objects must already be classified as TDP43_inclusion before morphology
 * subclassification can run.
 */

// ─── CHANGE THIS ───────────────────────────────────────────────────────────────
def RUN_MODE = "LABEL_CHECK"   // "LABEL_CHECK", "TRAIN_SAVE", or "LOAD_APPLY"
// ────────────────────────────────────────────────────────────────────────────────

// Minimum labelled examples required to include a class in training.
// Classes with fewer examples than this are skipped automatically.
def MIN_EXAMPLES = 5

def CLASS_INCLUSION   = "TDP43_inclusion"   // parent class from Stage 1
def CLASSIFIER_FILE   = buildFilePath(PROJECT_BASE_DIR, "pTDP43_morphology_classifier.txt")
def OUTPUT_CSV        = buildFilePath(PROJECT_BASE_DIR, "results", "morphology_counts.csv")

// All 7 morphology classes — unused ones are skipped gracefully during training
def ALL_MORPH_CLASSES = [
    "NCI_skein",
    "NCI_spherical",
    "NCI_pre_inclusion",
    "NII",
    "DN",
    "GCI_oligo",
    "GCI_astrocytic"
]

// Features to use for morphology classification.
// Shape features are primary — morphology is about form, not colour.
// Colour features included as secondary discriminators (e.g. DNs are faint).
def FEATURE_NAMES = [
    "feat: Area um2",
    "feat: Perimeter um",
    "feat: Circularity",
    "feat: Aspect ratio",
    "feat: Solidity",
    "feat: MaxFeret um",
    "feat: DAB proxy",
    "feat: Color range",
    "feat: Mean intensity",
    "feat: Blue fraction",
    "feat: Color std",
    "feat: Red mean",
    "feat: Green mean",
    "feat: Blue mean",
    "feat: Red std",
    "feat: Green std",
    "feat: Blue std"
]

import qupath.lib.objects.classes.PathClass
import static qupath.lib.gui.scripting.QPEx.*

def imageData = getCurrentImageData()
def server    = imageData.getServer()
def cal       = server.getPixelCalibration()
double pxW    = cal.hasPixelSizeMicrons() ? cal.getPixelWidthMicrons()  : 0.25
double pxH    = cal.hasPixelSizeMicrons() ? cal.getPixelHeightMicrons() : 0.25

println "=== pTDP-43 Morphology Classifier (QuPath 0.7.0) ==="
println "Mode: ${RUN_MODE}"


// ═════════════════════════════════════════════════════════════════════════════
//  LABEL_CHECK — show labelling progress per class
// ═════════════════════════════════════════════════════════════════════════════
if (RUN_MODE == "LABEL_CHECK") {
    def allDets = getDetectionObjects()

    // Count all detections by class
    def classCounts = [:]
    allDets.each { d ->
        def c = d.getPathClass()?.toString() ?: "Unclassified"
        classCounts[c] = (classCounts[c] ?: 0) + 1
    }

    println "\n--- All detections ---"
    classCounts.each { c, n -> println "  ${c}: ${n}" }

    println "\n--- Morphology labelling progress ---"
    int totalLabelled = 0
    ALL_MORPH_CLASSES.each { cls ->
        int n = classCounts[cls] ?: 0
        totalLabelled += n
        String status = n == 0 ? "(none yet)" : n < MIN_EXAMPLES ? "(needs ${MIN_EXAMPLES - n} more to train)" : "(OK)"
        println "  ${cls}: ${n} ${status}"
    }

    int inclUnlabelled = (classCounts[CLASS_INCLUSION] ?: 0)
    println "\n  Still labelled as '${CLASS_INCLUSION}' (not yet morphology-typed): ${inclUnlabelled}"
    println "  Total morphology labels assigned: ${totalLabelled}"
    println "\nREMINDER:"
    println "  Right-click a detection → Set classification → choose morphology class"
    println "  Only label objects that are genuine pTDP-43 inclusions"
    println "  Aim for ${MIN_EXAMPLES}+ examples per class you want to classify"
    println "  Classes with fewer than ${MIN_EXAMPLES} examples will be skipped in training"
}


// ═════════════════════════════════════════════════════════════════════════════
//  TRAIN_SAVE — train Gaussian Naive Bayes morphology classifier
// ═════════════════════════════════════════════════════════════════════════════
if (RUN_MODE == "TRAIN_SAVE") {
    def allDets = getDetectionObjects()
    println "\n[TRAIN_SAVE] Morphology classifier"

    // Find which classes have enough examples to train on
    def classGroups = [:]
    ALL_MORPH_CLASSES.each { cls ->
        def members = allDets.findAll { it.getPathClass()?.toString() == cls }
        if (members.size() >= MIN_EXAMPLES) {
            classGroups[cls] = members
            println "  ${cls}: ${members.size()} examples (included)"
        } else {
            println "  ${cls}: ${members.size() ?: 0} examples (SKIPPED — need ${MIN_EXAMPLES}+)"
        }
    }

    def activeClasses = classGroups.keySet() as List

    if (activeClasses.size() < 2) {
        println "\nERROR: Need at least 2 classes with ${MIN_EXAMPLES}+ examples to train."
        println "Label more objects and re-run TRAIN_SAVE."
        return
    }

    println "\nTraining on ${activeClasses.size()} classes: ${activeClasses}"

    // Check feat: measurements exist
    def firstObj = classGroups[activeClasses[0]][0]
    def availableFeats = (firstObj.getMeasurementList().getNames() as List).findAll { it.startsWith("feat:") }
    if (availableFeats.isEmpty()) {
        println "ERROR: No feat: measurements found. Run EXTRACT in the DAB classifier script first."
        return
    }

    // Use only features that exist on these objects
    def featNames = FEATURE_NAMES.findAll { availableFeats.contains(it) }
    println "  Using ${featNames.size()} features"

    // Compute per-class statistics (Gaussian Naive Bayes)
    println "\n  Feature separation analysis:"
    def featureStats = [:]  // class -> fname -> [mean, std]

    activeClasses.each { cls ->
        featureStats[cls] = [:]
        featNames.each { fname ->
            def vals = classGroups[cls].collect { det ->
                safeGet(det.getMeasurementList(), fname)
            }
            featureStats[cls][fname] = [calcMean(vals), calcStd(vals)]
        }
    }

    // Print separation for most discriminating features
    featNames.each { fname ->
        def means = activeClasses.collect { featureStats[it][fname][0] }
        double maxDiff = means.max() - means.min()
        double avgStd = activeClasses.collect { featureStats[it][fname][1] }.sum() / activeClasses.size()
        double sep = maxDiff / Math.max(avgStd, 1e-6)
        if (sep > 0.3) {
            println "    ${fname}: max-separation=${String.format('%.2f', sep)} ${sep > 1.0 ? '(GOOD)' : '(weak)'}"
        }
    }

    // Training accuracy — confusion matrix
    println "\n  Computing training accuracy..."
    def confusionMatrix = [:]
    activeClasses.each { actual ->
        confusionMatrix[actual] = [:]
        activeClasses.each { predicted -> confusionMatrix[actual][predicted] = 0 }
    }

    int correct = 0
    int total   = 0
    activeClasses.each { cls ->
        classGroups[cls].each { det ->
            String predicted = predictMultiGNB(det, activeClasses, featNames, featureStats)
            confusionMatrix[cls][predicted] = (confusionMatrix[cls][predicted] ?: 0) + 1
            if (predicted == cls) correct++
            total++
        }
    }

    double accuracy = (double) correct / total * 100.0
    println "  Overall accuracy: ${String.format('%.1f', accuracy)}% (${correct}/${total})"

    // Print confusion matrix
    println "\n  Confusion matrix (rows=actual, cols=predicted):"
    print "  " + String.format("%-22s", "")
    activeClasses.each { c -> print String.format("%-20s", c.take(18)) }
    println ""
    activeClasses.each { actual ->
        print "  " + String.format("%-22s", actual.take(20))
        activeClasses.each { predicted ->
            int n = confusionMatrix[actual][predicted] ?: 0
            print String.format("%-20s", n.toString())
        }
        println ""
    }

    if (accuracy < 50) {
        println "\n  WARNING: Low accuracy. Classes may be morphologically too similar"
        println "  for these features to distinguish. Consider:"
        println "    - Labelling more clear-cut examples of each class"
        println "    - Merging very similar classes"
    } else if (accuracy < 70) {
        println "\n  Moderate accuracy. Usable but label more examples for improvement."
    } else {
        println "\n  Good accuracy. Classifier looks reliable."
    }

    // Save classifier
    def sb = new StringBuilder()
    sb.append("# pTDP43 Morphology GNB Classifier\n")
    sb.append("activeClasses=${activeClasses.join('|')}\n")
    sb.append("featureNames=${featNames.join('|')}\n")
    activeClasses.each { cls ->
        featNames.each { fname ->
            def s = featureStats[cls][fname]
            sb.append("STAT|${cls}|${fname}|${s[0]}|${s[1]}\n")
        }
    }

    new File(CLASSIFIER_FILE).text = sb.toString()
    println "\n  Classifier saved: ${CLASSIFIER_FILE}"
    println "  Active classes: ${activeClasses}"
    println "  NEXT: Run LOAD_APPLY to apply morphology classification"
}


// ═════════════════════════════════════════════════════════════════════════════
//  LOAD_APPLY — apply morphology classifier to all TDP43_inclusion objects
// ═════════════════════════════════════════════════════════════════════════════
if (RUN_MODE == "LOAD_APPLY") {
    def cf = new File(CLASSIFIER_FILE)
    if (!cf.exists()) {
        println "ERROR: No classifier found at ${CLASSIFIER_FILE}"
        println "Run TRAIN_SAVE first."
        return
    }

    println "\n[LOAD_APPLY] Loading morphology classifier..."
    def activeClasses = []
    def featNames     = []
    def featureStats  = [:]  // class -> fname -> [mean, std]

    cf.readLines().each { line ->
        if (line.startsWith("activeClasses=")) {
            activeClasses = line.split("=")[1].split("\\|") as List
        }
        if (line.startsWith("featureNames=")) {
            featNames = line.split("=")[1].split("\\|") as List
        }
        if (line.startsWith("STAT|")) {
            def p = line.split("\\|")
            def cls   = p[1]
            def fname = p[2]
            if (!featureStats[cls]) featureStats[cls] = [:]
            featureStats[cls][fname] = [p[3] as double, p[4] as double]
        }
    }

    println "  Classes: ${activeClasses}"
    println "  Features: ${featNames.size()}"

    // Get all TDP43_inclusion detections
    def inclusions = getDetectionObjects().findAll {
        it.getPathClass()?.toString() == CLASS_INCLUSION
    }

    // Also include any objects already assigned a morphology class
    // (from previous partial labelling) that should be reclassified
    def alreadyMorphed = getDetectionObjects().findAll { det ->
        ALL_MORPH_CLASSES.contains(det.getPathClass()?.toString())
    }

    def toClassify = inclusions + alreadyMorphed
    println "  Objects to classify: ${toClassify.size()}"

    if (toClassify.isEmpty()) {
        println "ERROR: No TDP43_inclusion objects found."
        println "Run DAB classifier LOAD_APPLY first, then run this script."
        return
    }

    // Check features exist
    def hasFeat = (toClassify[0].getMeasurementList().getNames() as List).any { it.startsWith("feat:") }
    if (!hasFeat) {
        println "ERROR: No feat: measurements. Run EXTRACT in the DAB classifier script first."
        return
    }

    // Classify each inclusion
    def morphCounts = [:]
    activeClasses.each { morphCounts[it] = 0 }

    toClassify.each { det ->
        String morphClass = predictMultiGNB(det, activeClasses, featNames, featureStats)
        det.setPathClass(PathClass.fromString(morphClass))
        morphCounts[morphClass] = (morphCounts[morphClass] ?: 0) + 1
    }

    println "\n  Morphology classification results:"
    morphCounts.each { cls, n -> println "    ${cls}: ${n}" }

    fireHierarchyUpdate()

    // Export per-annotation per-class counts
    def annotations = getAnnotationObjects()
    def imageName   = ""
    try { imageName = getProjectEntry().getImageName() } catch (Exception e) { imageName = "Unknown" }

    println "\n  === RESULTS ==="

    def resultsDir = new File(buildFilePath(PROJECT_BASE_DIR, "results"))
    if (!resultsDir.exists()) resultsDir.mkdirs()

    def csvFile = new File(OUTPUT_CSV)
    boolean isNew = !csvFile.exists()
    def writer = csvFile.newWriter(true)

    // Header: Image, Annotation, Area_mm2, then one column per active class, then total
    if (isNew) {
        def header = "Image,Annotation,Area_mm2," + activeClasses.join(",") + ",Total_inclusions,Density_per_mm2"
        writer.writeLine(header)
    }

    if (annotations.isEmpty()) {
        double aMM2 = (server.getWidth() * pxW * server.getHeight() * pxH) / 1e6
        def allMorphDets = getDetectionObjects().findAll { det ->
            activeClasses.contains(det.getPathClass()?.toString())
        }
        int total = allMorphDets.size()
        def counts = activeClasses.collect { cls ->
            allMorphDets.count { it.getPathClass()?.toString() == cls }
        }
        double density = (aMM2 > 0) ? total / aMM2 : 0
        println "  Whole image: total=${total} | ${activeClasses.collect { cls -> cls + "=" + counts[activeClasses.indexOf(cls)] }.join(', ')}"
        writer.writeLine("${imageName},Whole image,${fmt(aMM2)},${counts.join(',')},${total},${fmt(density)}")
    } else {
        annotations.each { ann ->
            def name    = ann.getName() ?: ann.getPathClass()?.toString() ?: "Unnamed"
            double aMM2 = (ann.getROI().getArea() * pxW * pxH) / 1e6

            // Count each morphology class within this annotation
            def counts = activeClasses.collect { cls ->
                getDetectionObjects().count { det ->
                    det.getPathClass()?.toString() == cls &&
                    ann.getROI().contains(det.getROI().getCentroidX(), det.getROI().getCentroidY())
                }
            }

            int total  = counts.sum()
            double density = (aMM2 > 0) ? total / aMM2 : 0

            def countStr = activeClasses.withIndex().collect { cls, i ->
                "${cls}=${counts[i]}"
            }.join(', ')

            println "  ${name}: total=${total} | ${countStr} | ${fmt(density)}/mm²"
            writer.writeLine("${imageName},${name},${fmt(aMM2)},${counts.join(',')},${total},${fmt(density)}")
        }
    }

    writer.close()
    println "\n  CSV: ${OUTPUT_CSV}"
    println "=== Done ==="
}


// ═════════════════════════════════════════════════════════════════════════════
//  UTILITIES
// ═════════════════════════════════════════════════════════════════════════════

// Predict the most likely class using Gaussian Naive Bayes (multiclass)
def predictMultiGNB(det, List classes, List featNames, Map featureStats) {
    def ml = det.getMeasurementList()
    def scores = [:]
    classes.each { cls ->
        double score = 0.0
        featNames.each { fname ->
            double v = safeGet(ml, fname)
            def s = featureStats[cls][fname]
            if (s) score += gaussLL(v, s[0], s[1])
        }
        scores[cls] = score
    }
    return scores.max { it.value }.key
}

def gaussLL(double x, double mean, double std) {
    double s = Math.max(std, 1e-6)
    return -0.5 * Math.log(2 * Math.PI * s * s) - 0.5 * ((x - mean) / s) * ((x - mean) / s)
}

def safeGet(ml, String name) {
    try { double v = ml.get(name); return Double.isNaN(v) ? 0.0 : v }
    catch (Exception e) { return 0.0 }
}

def calcMean(List vals) {
    if (vals.isEmpty()) return 0.0
    return vals.sum() / vals.size()
}

def calcStd(List vals) {
    if (vals.size() < 2) return 1e-6
    double m = calcMean(vals)
    double v = vals.collect { (it - m) * (it - m) }.sum() / vals.size()
    return Math.sqrt(v) + 1e-6
}

def fmt(double v) { return String.format('%.4f', v) }


/**
 * ═══════════════════════════════════════════════════════════════════
 *  README — COMPLETE 3-SCRIPT WORKFLOW
 * ═══════════════════════════════════════════════════════════════════
 *
 *  THREE SCRIPTS (run in this order):
 *
 *  1. pTDP43_nuclei_counter.groovy
 *     Counts haematoxylin-stained nuclei per annotation.
 *     Run BEFORE the DAB classifier.
 *
 *  2. pTDP43_DAB_classifier.groovy
 *     Separates real pTDP-43 DAB signal from artefacts.
 *     Run after nuclei counter, before morphology classifier.
 *
 *  3. 03_morphology_classifier.groovy  ← this script
 *     Subclassifies real inclusions into morphological categories.
 *     Run last, after DAB classifier has identified real inclusions.
 *
 *  OUTPUT FILES:
 *    results/nuclei_counts.csv       ← from Script 1
 *    results/pTDP43_counts.csv       ← from Script 2
 *    results/morphology_counts.csv   ← from Script 3
 *
 *  Combine in Excel using Annotation column as the common key.
 */
