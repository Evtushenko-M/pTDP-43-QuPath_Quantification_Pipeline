/**
 * pTDP-43 DAB Artefact Classifier v3 — QuPath 0.7.0 — with nuclei counting
 * ==========================================================================
 * MODES:
 *   "DIAGNOSE"    → prints measurements on detections
 *   "EXTRACT"     → adds shape + intensity features to DAB detections
 *   "TRAIN_SAVE"  → trains classifier from labels, saves to disk
 *   "LOAD_APPLY"  → loads classifier, removes artefacts, counts nuclei,
 *                   exports inclusions + nuclei + ratio to CSV
 *
 * WORKFLOW — first time:
 *   1. Pixel classifier → Create Objects (DAB detections) → Ctrl+S
 *   2. DIAGNOSE
 *   3. EXTRACT
 *   4. Label detections as TDP43_inclusion or DAB_artefact
 *   5. TRAIN_SAVE
 *   6. UNLOCK all annotations (right-click → Unlock)
 *   7. LOAD_APPLY
 *
 * SUBSEQUENT SLIDES:
 *   1. Pixel classifier → Create Objects → Ctrl+S
 *   2. UNLOCK all annotations
 *   3. EXTRACT
 *   4. LOAD_APPLY
 *
 * IMPORTANT: Annotations MUST be unlocked before LOAD_APPLY runs.
 * Cell detection cannot run inside locked annotations.
 *
 * NUCLEI THRESHOLD TUNING:
 *   After LOAD_APPLY, check the nuclei count visually.
 *   Raise NUCLEI_THRESHOLD if too many non-nuclear objects are detected.
 *   Lower it if genuine nuclei are being missed.
 */

// ─── CHANGE THIS ───────────────────────────────────────────────────────────────
def RUN_MODE = "LOAD_APPLY"    // "DIAGNOSE", "EXTRACT", "TRAIN_SAVE", or "LOAD_APPLY"
// ────────────────────────────────────────────────────────────────────────────────

// ─── NUCLEI DETECTION PARAMETERS — tune for your tissue ───────────────────────
def NUCLEI_THRESHOLD    = 0.10   // haematoxylin OD — raise for fewer nuclei, lower for more
def NUCLEI_MIN_AREA     = 15.0   // minimum nucleus area in µm²
def NUCLEI_MAX_AREA     = 350.0  // maximum nucleus area in µm²
def NUCLEI_SIGMA        = 1.5    // smoothing in µm
def NUCLEI_BG_RADIUS    = 8.0    // background subtraction radius in µm
// ─────────────────────────────────────────────────────────────────────────────

def CLASS_INCLUSION = "TDP43_inclusion"
def CLASS_ARTEFACT  = "DAB_artefact"
def CLASSIFIER_FILE = buildFilePath(PROJECT_BASE_DIR, "pTDP43_classifier.txt")
def OUTPUT_CSV      = buildFilePath(PROJECT_BASE_DIR, "results", "pTDP43_counts.csv")

import qupath.lib.objects.classes.PathClass
import qupath.lib.roi.RoiTools
import qupath.lib.analysis.features.ObjectMeasurements
import static qupath.lib.gui.scripting.QPEx.*

def imageData = getCurrentImageData()
def server    = imageData.getServer()
def cal       = server.getPixelCalibration()
double pxW    = cal.hasPixelSizeMicrons() ? cal.getPixelWidthMicrons()  : 0.25
double pxH    = cal.hasPixelSizeMicrons() ? cal.getPixelHeightMicrons() : 0.25

println "=== pTDP-43 Classifier v3 with Nuclei Counting (QuPath 0.7.0) ==="
println "Mode: ${RUN_MODE}"
println "Pixel size: ${pxW} x ${pxH} µm"


// ═════════════════════════════════════════════════════════════════════════════
//  DIAGNOSE
// ═════════════════════════════════════════════════════════════════════════════
if (RUN_MODE == "DIAGNOSE") {
    def dets = getDetectionObjects()
    println "\nTotal detections: ${dets.size()}"
    if (dets.isEmpty()) { println "No detections found."; return }

    dets.take(3).eachWithIndex { det, i ->
        def cls = det.getPathClass()?.toString() ?: "Unclassified"
        println "\n--- Detection ${i + 1} (class: ${cls}) ---"
        def ml = det.getMeasurementList()
        def names = ml.getNames() as List
        if (names.isEmpty()) { println "  (no measurements)" }
        else { names.each { n -> println "  '${n}' = ${safeGet(ml, n)}" } }
    }

    def classCounts = [:]
    dets.each { d ->
        def c = d.getPathClass()?.toString() ?: "Unclassified"
        classCounts[c] = (classCounts[c] ?: 0) + 1
    }
    println "\nBy class:"
    classCounts.each { c, n -> println "  ${c}: ${n}" }

    // Check annotation lock status
    println "\nAnnotations:"
    getAnnotationObjects().each { ann ->
        println "  '${ann.getName() ?: 'Unnamed'}' | locked: ${ann.isLocked()}"
    }
    if (getAnnotationObjects().any { it.isLocked() }) {
        println "\nWARNING: Locked annotations detected. Unlock them before running LOAD_APPLY."
        println "  Right-click each annotation → Unlock"
    }

    def hasFeat = (dets[0].getMeasurementList().getNames() as List).any { it.startsWith("feat:") }
    println hasFeat ? "\nfeat: measurements present." : "\nNo feat: measurements. Run EXTRACT."
}


// ═════════════════════════════════════════════════════════════════════════════
//  EXTRACT
// ═════════════════════════════════════════════════════════════════════════════
if (RUN_MODE == "EXTRACT") {
    def dets = getDetectionObjects()
    println "\n[EXTRACT] ${dets.size()} detections."
    if (dets.isEmpty()) { println "ERROR: No detections."; return }

    def measTypes  = [
        ObjectMeasurements.Measurements.MEAN,
        ObjectMeasurements.Measurements.MAX,
        ObjectMeasurements.Measurements.MIN,
        ObjectMeasurements.Measurements.STD_DEV
    ]
    def compartments = [ObjectMeasurements.Compartments.CELL]

    int done = 0
    dets.each { det ->
        def ml  = det.getMeasurementList()
        def roi = det.getROI()

        double area  = roi.getArea() * pxW * pxH
        double perim = roi.getLength() * ((pxW + pxH) / 2.0)
        double circ  = (perim > 0) ? (4.0 * Math.PI * area) / (perim * perim) : 0.0
        double bW    = roi.getBoundsWidth()  * pxW
        double bH    = roi.getBoundsHeight() * pxH
        double aspect = (Math.min(bW, bH) > 0) ? Math.max(bW, bH) / Math.min(bW, bH) : 1.0

        double solidity = 1.0
        try {
            def hull = RoiTools.getConvexHull(roi)
            double ha = hull.getArea() * pxW * pxH
            solidity = (ha > 0) ? area / ha : 1.0
        } catch (Exception ignored) {}

        double feret = Math.sqrt(bW * bW + bH * bH)

        ml.put("feat: Area um2",     area)
        ml.put("feat: Perimeter um", perim)
        ml.put("feat: Circularity",  circ)
        ml.put("feat: Aspect ratio", aspect)
        ml.put("feat: Solidity",     solidity)
        ml.put("feat: MaxFeret um",  feret)

        try {
            ObjectMeasurements.addIntensityMeasurements(server, det, 1.0, measTypes, compartments)
        } catch (Exception ignored) {}

        double rMean = safeGet(ml, "Red: Mean")
        double gMean = safeGet(ml, "Green: Mean")
        double bMean = safeGet(ml, "Blue: Mean")
        double rStd  = safeGet(ml, "Red: Std.Dev.")
        double gStd  = safeGet(ml, "Green: Std.Dev.")
        double bStd  = safeGet(ml, "Blue: Std.Dev.")

        double dabProxy    = rMean - bMean
        double colorRange  = Math.max(rMean, Math.max(gMean, bMean)) -
                             Math.min(rMean, Math.min(gMean, bMean))
        double meanIntensity = (rMean + gMean + bMean) / 3.0
        double blueFraction  = (meanIntensity > 0) ? bMean / meanIntensity : 1.0
        double colorStd      = (rStd + gStd + bStd) / 3.0

        ml.put("feat: Red mean",       rMean)
        ml.put("feat: Green mean",     gMean)
        ml.put("feat: Blue mean",      bMean)
        ml.put("feat: Red std",        rStd)
        ml.put("feat: Green std",      gStd)
        ml.put("feat: Blue std",       bStd)
        ml.put("feat: DAB proxy",      dabProxy)
        ml.put("feat: Color range",    colorRange)
        ml.put("feat: Mean intensity", meanIntensity)
        ml.put("feat: Blue fraction",  blueFraction)
        ml.put("feat: Color std",      colorStd)

        done++
        if (done % 200 == 0) println "  ${done} / ${dets.size()}"
    }

    fireHierarchyUpdate()

    def vml = dets[0].getMeasurementList()
    println "\nDone. First detection:"
    ["feat: Area um2", "feat: Circularity", "feat: DAB proxy",
     "feat: Red mean", "feat: Blue mean", "feat: Blue fraction"].each { f ->
        println "  ${f} = ${safeGet(vml, f)}"
    }
    def totalFeats = (vml.getNames() as List).count { it.startsWith("feat:") }
    println "\nTotal feat: measurements: ${totalFeats}"
    println "NEXT: Label detections then run TRAIN_SAVE"
}


// ═════════════════════════════════════════════════════════════════════════════
//  TRAIN_SAVE
// ═════════════════════════════════════════════════════════════════════════════
if (RUN_MODE == "TRAIN_SAVE") {
    def dets = getDetectionObjects()
    println "\n[TRAIN_SAVE]"

    def inclusions = dets.findAll { it.getPathClass()?.toString() == CLASS_INCLUSION }
    def artefacts  = dets.findAll { it.getPathClass()?.toString() == CLASS_ARTEFACT }

    println "  ${CLASS_INCLUSION}: ${inclusions.size()}"
    println "  ${CLASS_ARTEFACT}:  ${artefacts.size()}"

    if (inclusions.size() < 5 || artefacts.size() < 5) {
        println "ERROR: Need at least 5 of each class."; return
    }

    def testNames = inclusions[0].getMeasurementList().getNames() as List
    def featNames = testNames.findAll { it.startsWith("feat:") }
    if (featNames.isEmpty()) { println "ERROR: No feat: measurements. Run EXTRACT first."; return }
    println "  Features: ${featNames.size()}"

    println "\n  Feature analysis:"
    def featureStats = [:]

    featNames.each { fname ->
        def inclVals = collectValues(inclusions, fname)
        def arteVals = collectValues(artefacts, fname)
        double inclMean = calcMean(inclVals)
        double inclStd  = calcStd(inclVals)
        double arteMean = calcMean(arteVals)
        double arteStd  = calcStd(arteVals)
        featureStats[fname] = [inclMean, inclStd, arteMean, arteStd]
        double sep = Math.abs(inclMean - arteMean) / Math.max(inclStd + arteStd, 1e-6)
        String quality = sep > 0.5 ? "GOOD" : (sep > 0.2 ? "weak" : "poor")
        println "    ${fname}: sep=${fmt(sep)} (${quality})"
        println "      Incl: ${fmt(inclMean)} ± ${fmt(inclStd)}"
        println "      Arte: ${fmt(arteMean)} ± ${fmt(arteStd)}"
    }

    int nTotal = inclusions.size() + artefacts.size()
    double logPriorIncl = Math.log((double) inclusions.size() / nTotal)
    double logPriorArte = Math.log((double) artefacts.size() / nTotal)

    int correct = 0
    (inclusions + artefacts).each { det ->
        boolean actualIncl = det.getPathClass()?.toString() == CLASS_INCLUSION
        boolean predicted  = predictGNB(det, featNames, featureStats, logPriorIncl, logPriorArte)
        if (predicted == actualIncl) correct++
    }
    double accuracy = (double) correct / nTotal * 100.0
    println "\n  Training accuracy: ${fmt(accuracy)}% (${correct}/${nTotal})"
    if (accuracy < 60)       println "  WARNING: Low accuracy."
    else if (accuracy < 80)  println "  Moderate — usable."
    else                     println "  Good."

    def sb = new StringBuilder()
    sb.append("# pTDP43 GNB Classifier\n")
    sb.append("nInclusions=${inclusions.size()}\n")
    sb.append("nArtefacts=${artefacts.size()}\n")
    sb.append("logPriorIncl=${logPriorIncl}\n")
    sb.append("logPriorArte=${logPriorArte}\n")
    featNames.each { fname ->
        def s = featureStats[fname]
        sb.append("FEAT|${fname}|${s[0]}|${s[1]}|${s[2]}|${s[3]}\n")
    }
    new File(CLASSIFIER_FILE).text = sb.toString()
    println "\n  Saved to: ${CLASSIFIER_FILE}"
    println "  NEXT: Unlock annotations, then run LOAD_APPLY"
}


// ═════════════════════════════════════════════════════════════════════════════
//  LOAD_APPLY — classify DAB detections + count nuclei + export ratio
// ═════════════════════════════════════════════════════════════════════════════
if (RUN_MODE == "LOAD_APPLY") {

    // ── Check annotations are unlocked ────────────────────────────────────────
    def lockedAnns = getAnnotationObjects().findAll { it.isLocked() }
    if (!lockedAnns.isEmpty()) {
        println "ERROR: ${lockedAnns.size()} annotation(s) are locked."
        println "  Right-click each annotation in the Annotations panel → Unlock"
        println "  Then re-run LOAD_APPLY."
        return
    }

    // ── Load classifier ───────────────────────────────────────────────────────
    def cf = new File(CLASSIFIER_FILE)
    if (!cf.exists()) { println "ERROR: No classifier at ${CLASSIFIER_FILE}"; return }

    println "\n[LOAD_APPLY] Loading classifier..."
    def featNames    = []
    def featureStats = [:]
    double logPriorIncl = 0, logPriorArte = 0

    cf.readLines().each { line ->
        if (line.startsWith("logPriorIncl=")) logPriorIncl = line.split("=")[1] as double
        if (line.startsWith("logPriorArte=")) logPriorArte = line.split("=")[1] as double
        if (line.startsWith("FEAT|")) {
            def p = line.split("\\|")
            featNames << p[1]
            featureStats[p[1]] = [p[2] as double, p[3] as double, p[4] as double, p[5] as double]
        }
    }
    println "  ${featNames.size()} features loaded."

    // ── Step 1: Classify DAB detections ──────────────────────────────────────
    def dets = getDetectionObjects()
    println "  DAB detections: ${dets.size()}"
    if (dets.isEmpty()) { println "ERROR: No detections."; return }

    def hasFeat = (dets[0].getMeasurementList().getNames() as List).any { it.startsWith("feat:") }
    if (!hasFeat) { println "ERROR: No feat: measurements. Run EXTRACT first."; return }

    def inclClass = PathClass.fromString(CLASS_INCLUSION)
    def arteClass = PathClass.fromString(CLASS_ARTEFACT)
    int nIncl = 0, nArte = 0

    dets.each { det ->
        if (predictGNB(det, featNames, featureStats, logPriorIncl, logPriorArte)) {
            det.setPathClass(inclClass)
            nIncl++
        } else {
            det.setPathClass(arteClass)
            nArte++
        }
    }
    println "  Classified: ${CLASS_INCLUSION}=${nIncl}, ${CLASS_ARTEFACT}=${nArte}"

    def toRemove = getDetectionObjects().findAll { it.getPathClass()?.toString() == CLASS_ARTEFACT }
    println "  Removing ${toRemove.size()} artefacts..."
    removeObjects(toRemove, true)
    fireHierarchyUpdate()

    // Record inclusions before nuclei detection adds new objects
    def inclusionDets = getDetectionObjects().findAll {
        it.getPathClass()?.toString() == CLASS_INCLUSION
    }
    println "  Confirmed inclusions: ${inclusionDets.size()}"

    // ── Step 2: Detect nuclei ─────────────────────────────────────────────────
    println "\n  Detecting nuclei..."
    println "  Threshold: ${NUCLEI_THRESHOLD} OD | Min: ${NUCLEI_MIN_AREA} µm² | Max: ${NUCLEI_MAX_AREA} µm²"

    // Use correct parameter names from QuPath 0.7.0
    runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', [
        'detectionImageBrightfield' : 'Hematoxylin OD',
        'requestedPixelSizeMicrons' : 0.5,
        'backgroundRadiusMicrons'   : NUCLEI_BG_RADIUS,
        'backgroundByReconstruction': true,
        'medianRadiusMicrons'       : 0.0,
        'sigmaMicrons'              : NUCLEI_SIGMA,
        'minAreaMicrons'            : NUCLEI_MIN_AREA,
        'maxAreaMicrons'            : NUCLEI_MAX_AREA,
        'threshold'                 : NUCLEI_THRESHOLD,
        'maxBackground'             : 2.0,
        'watershedPostProcess'      : true,
        'excludeDAB'                : true,   // ignore DAB-positive regions — nuclei only
        'cellExpansionMicrons'      : 0.0,
        'includeNuclei'             : true,
        'smoothBoundaries'          : true,
        'makeMeasurements'          : false
    ])

    // After cell detection: all detections = inclusions (classified) + nuclei (unclassified)
    def allDetsAfter = getDetectionObjects()
    def nucleiDets = allDetsAfter.findAll { it.getPathClass() == null }

    println "  Nuclei detected: ${nucleiDets.size()}"

    if (nucleiDets.size() == 0) {
        println "  WARNING: 0 nuclei detected."
        println "  Try lowering NUCLEI_THRESHOLD (currently ${NUCLEI_THRESHOLD})."
        println "  Also confirm annotations are unlocked and haematoxylin staining is present."
    }

    fireHierarchyUpdate()

    // ── Step 3: Count per annotation and export ───────────────────────────────
    def annotations = getAnnotationObjects()
    def imageName = ""
    try { imageName = getProjectEntry().getImageName() } catch (Exception e) { imageName = "Unknown" }

    println "\n  === RESULTS ==="

    def resultsDir = new File(buildFilePath(PROJECT_BASE_DIR, "results"))
    if (!resultsDir.exists()) resultsDir.mkdirs()

    def csvFile = new File(OUTPUT_CSV)
    boolean isNew = !csvFile.exists()
    def writer = csvFile.newWriter(true)
    if (isNew) writer.writeLine("Image,Annotation,Area_mm2,Inclusions,Nuclei,Inclusions_per_nucleus,Density_per_mm2")

    if (annotations.isEmpty()) {
        int totalIncl   = inclusionDets.size()
        int totalNuclei = nucleiDets.size()
        double ratio    = (totalNuclei > 0) ? (double) totalIncl / totalNuclei : 0
        double aMM2     = (server.getWidth() * pxW * server.getHeight() * pxH) / 1e6
        println "  Whole image: ${totalIncl} inclusions | ${totalNuclei} nuclei | ratio ${fmt(ratio)}"
        writer.writeLine("${imageName},Whole image,${fmt(aMM2)},${totalIncl},${totalNuclei},${fmt(ratio)},${fmt(totalIncl/aMM2)}")
    } else {
        annotations.each { ann ->
            def name    = ann.getName() ?: ann.getPathClass()?.toString() ?: "Unnamed"
            double aMM2 = (ann.getROI().getArea() * pxW * pxH) / 1e6

            int inclCount = inclusionDets.count { det ->
                ann.getROI().contains(det.getROI().getCentroidX(), det.getROI().getCentroidY())
            }
            int nucleiCount = nucleiDets.count { det ->
                ann.getROI().contains(det.getROI().getCentroidX(), det.getROI().getCentroidY())
            }

            double ratio   = (nucleiCount > 0) ? (double) inclCount / nucleiCount : 0
            double density = (aMM2 > 0) ? inclCount / aMM2 : 0

            println "  ${name}: ${inclCount} inclusions | ${nucleiCount} nuclei | ratio ${fmt(ratio)} | ${fmt(density)}/mm2"
            writer.writeLine("${imageName},${name},${fmt(aMM2)},${inclCount},${nucleiCount},${fmt(ratio)},${fmt(density)}")
        }
    }

    writer.close()
    println "\n  CSV: ${OUTPUT_CSV}"
    println ""
    println "  Nuclei objects remain visible in the hierarchy (unclassified = no colour)."
    println "  To remove them after QC: select all unclassified detections → Delete"
    println "=== Done ==="
}


// ═════════════════════════════════════════════════════════════════════════════
//  UTILITIES
// ═════════════════════════════════════════════════════════════════════════════

def safeGet(ml, String name) {
    try { double v = ml.get(name); return Double.isNaN(v) ? 0.0 : v }
    catch (Exception e) { return 0.0 }
}

def collectValues(List dets, String fname) {
    return dets.collect { safeGet(it.getMeasurementList(), fname) }
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

def gaussLL(double x, double mean, double std) {
    double s = Math.max(std, 1e-6)
    return -0.5 * Math.log(2 * Math.PI * s * s) - 0.5 * ((x - mean) / s) * ((x - mean) / s)
}

def predictGNB(det, List featNames, Map stats, double lpIncl, double lpArte) {
    def ml = det.getMeasurementList()
    double sI = lpIncl, sA = lpArte
    featNames.each { f ->
        double v = safeGet(ml, f)
        def s = stats[f]
        sI += gaussLL(v, s[0], s[1])
        sA += gaussLL(v, s[2], s[3])
    }
    return sI > sA
}

def fmt(double v) { return String.format('%.4f', v) }
