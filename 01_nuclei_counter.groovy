/**
 * Nuclei Counter — QuPath 0.7.0
 * ================================
 * Run this BEFORE your DAB quantification script.
 * Counts nuclei per annotation using haematoxylin channel.
 * Saves counts to a separate CSV: nuclei_counts.csv
 * Then deletes the nuclei detections to restore clean hierarchy
 * so your DAB quantification script can run normally afterwards.
 *
 * STEP BY STEP:
 *   1. Draw annotations and run pixel classifier → Create Objects (DAB) as normal
 *   2. Ctrl+S to save
 *   3. UNLOCK all annotations (right-click → Unlock)
 *   4. Run THIS script → saves nuclei counts to nuclei_counts.csv
 *      → deletes nuclei objects → your DAB objects remain intact
 *   5. Run your normal classifier script (EXTRACT → LOAD_APPLY)
 *      → saves inclusion counts to pTDP43_counts.csv
 *   6. Combine the two CSVs in Excel using the annotation name as the key
 *
 * TUNE THESE PARAMETERS FOR YOUR TISSUE:
 */

def NUCLEI_THRESHOLD = 0.10   // haematoxylin OD — lower = more nuclei detected
def NUCLEI_MIN_AREA  = 15.0   // µm² minimum — filters debris
def NUCLEI_MAX_AREA  = 350.0  // µm² maximum — filters folds
def NUCLEI_SIGMA     = 1.5    // smoothing in µm
def NUCLEI_BG_RADIUS = 8.0    // background radius in µm

def OUTPUT_CSV = buildFilePath(PROJECT_BASE_DIR, "results", "nuclei_counts.csv")

import static qupath.lib.gui.scripting.QPEx.*

def imageData = getCurrentImageData()
def server    = imageData.getServer()
def cal       = server.getPixelCalibration()
double pxW    = cal.hasPixelSizeMicrons() ? cal.getPixelWidthMicrons()  : 0.25
double pxH    = cal.hasPixelSizeMicrons() ? cal.getPixelHeightMicrons() : 0.25

println "=== Nuclei Counter (QuPath 0.7.0) ==="
println "Pixel size: ${pxW} x ${pxH} µm"

// ── Check annotations are unlocked ───────────────────────────────────────────
def lockedAnns = getAnnotationObjects().findAll { it.isLocked() }
if (!lockedAnns.isEmpty()) {
    println "ERROR: ${lockedAnns.size()} annotation(s) are locked."
    println "  Right-click each annotation → Unlock, then re-run."
    return
}

// ── Record existing DAB detections BEFORE nuclei detection ────────────────────
def dabDetsBefore = getDetectionObjects().collect { it }
println "DAB detections currently in hierarchy: ${dabDetsBefore.size()}"
println "These will be preserved."

// ── Run nuclei detection ──────────────────────────────────────────────────────
println "\nDetecting nuclei..."
println "Threshold: ${NUCLEI_THRESHOLD} | Min area: ${NUCLEI_MIN_AREA} µm² | Max: ${NUCLEI_MAX_AREA} µm²"

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
    'excludeDAB'                : true,
    'cellExpansionMicrons'      : 0.0,
    'includeNuclei'             : true,
    'smoothBoundaries'          : true,
    'makeMeasurements'          : false
])

fireHierarchyUpdate()

// After nuclei detection: hierarchy contains nuclei objects (unclassified)
// DAB objects were removed by the plugin — we will restore them shortly
def nucleiDets = getDetectionObjects().findAll { it.getPathClass() == null }
println "Nuclei detected: ${nucleiDets.size()}"

if (nucleiDets.size() == 0) {
    println "WARNING: 0 nuclei detected. Try lowering NUCLEI_THRESHOLD."
    println "Restoring DAB detections..."
    // Restore DAB detections even if nuclei count failed
    addObjects(dabDetsBefore)
    fireHierarchyUpdate()
    println "DAB detections restored. Script stopped."
    return
}

// ── Count nuclei per annotation ───────────────────────────────────────────────
def annotations = getAnnotationObjects()
def imageName = ""
try { imageName = getProjectEntry().getImageName() } catch (Exception e) { imageName = "Unknown" }

println "\n=== NUCLEI COUNTS ==="

def resultsDir = new File(buildFilePath(PROJECT_BASE_DIR, "results"))
if (!resultsDir.exists()) resultsDir.mkdirs()

def csvFile = new File(OUTPUT_CSV)
boolean isNew = !csvFile.exists()
def writer = csvFile.newWriter(true)
if (isNew) writer.writeLine("Image,Annotation,Area_mm2,Nuclei,Nuclei_per_mm2")

if (annotations.isEmpty()) {
    int total = nucleiDets.size()
    double aMM2 = (server.getWidth() * pxW * server.getHeight() * pxH) / 1e6
    println "  Whole image: ${total} nuclei | ${String.format('%.3f', aMM2)} mm²"
    writer.writeLine("${imageName},Whole image,${String.format('%.4f',aMM2)},${total},${String.format('%.4f',total/aMM2)}")
} else {
    annotations.each { ann ->
        def name    = ann.getName() ?: ann.getPathClass()?.toString() ?: "Unnamed"
        double aMM2 = (ann.getROI().getArea() * pxW * pxH) / 1e6
        int count   = nucleiDets.count { det ->
            ann.getROI().contains(det.getROI().getCentroidX(), det.getROI().getCentroidY())
        }
        double density = (aMM2 > 0) ? count / aMM2 : 0
        println "  ${name}: ${count} nuclei | ${String.format('%.3f', aMM2)} mm² | ${String.format('%.2f', density)}/mm²"
        writer.writeLine("${imageName},${name},${String.format('%.4f',aMM2)},${count},${String.format('%.4f',density)}")
    }
}

writer.close()
println "\nNuclei counts saved to: ${OUTPUT_CSV}"

// ── Remove nuclei detections and restore DAB detections ──────────────────────
println "\nRemoving nuclei detections..."
removeObjects(nucleiDets, true)

println "Restoring ${dabDetsBefore.size()} DAB detections..."
addObjects(dabDetsBefore)
fireHierarchyUpdate()

println "\nHierarchy restored. DAB detections are back."
println "You can now run your normal EXTRACT → LOAD_APPLY script."
println "=== Done ==="
