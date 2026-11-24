#@ ImagePlus imp
/*
 * SAMJ HRF AutoPrompter (Groovy script)
 *
 * Segment Hyperreflective Foci (HRF) in OCT images WITHOUT placing a prompt by
 * hand on every focus. The script finds bright, punctate candidates and feeds
 * them to SAMJ as preset prompts automatically.
 *
 * How to use:
 *   1. Open your OCT image or stack in Fiji (a single B-scan or a whole volume).
 *   2. Run this script: Plugins > SAMJ > SAMJ HRF AutoPrompter.
 *   3. Pick a SAM model and click Load model (only slow the first time).
 *   4. Choose the scope (current slice or whole stack) and, if needed, tune the
 *      detector (prominence, minimum brightness, prompt type and size).
 *   5. Click "Detect HRF and segment". The script, per slice:
 *        a. finds bright local maxima (candidate foci) with ImageJ's
 *           MaximumFinder, keeping only the ones above the brightness floor,
 *        b. turns each candidate into a preset box (or point) prompt,
 *        c. runs SAMJ once on the slice with all those prompts,
 *        d. paints the returned masks into a companion label stack
 *           ("<title>_HRF_Labels") and, optionally, drops the detected points
 *           into the ROI Manager so you can see what was prompted.
 *   6. Click "Save HRF labels" when finished.
 *
 * The detector is a simple candidate finder, not a trained HRF classifier. The
 * prominence (noise tolerance) is what keeps it on small punctate foci rather
 * than on the long continuous bright retinal bands: a flat bright band has few
 * local maxima, whereas foci are distinct peaks. Tune the brightness floor and
 * prominence for your data, and use SAMJ's interactive annotator to correct any
 * misses.
 *
 * Boundary quality for small foci: the default prompt is a small box, not a
 * point, and SAMJ keeps only the largest ROI per prompt, so SAM is far less
 * likely to flood out into the bright retinal bands than a bare point would be.
 * SAM also rescales its input to 1024 px, which can shrink a tiny focus to
 * sub-pixel on a full B-scan. Tick "Segment in a tile around each focus" to run
 * SAM on a small native-resolution crop around each candidate instead; the focus
 * then stays large in SAM's view. This costs one segmentation per focus rather
 * than per slice, so it is slower but much kinder to the smallest foci.
 *
 * Under the hood it calls SAMJ's public static Java API
 * ai.nets.samj.ij.SAMJ_Annotator.samJReturnMask(model, slice_rai, points, rects)
 * per slice, exactly like the manual annotator does.
 */

import ai.nets.samj.ij.SAMJ_Annotator
import ai.nets.samj.install.SamEnvManagerAbstract
import ai.nets.samj.ij.utils.Constants
import ai.nets.samj.communication.model.SAM2Tiny
import ai.nets.samj.communication.model.SAM2Small
import ai.nets.samj.communication.model.SAM2Large
import ai.nets.samj.communication.model.EfficientSAM
import ai.nets.samj.communication.model.EfficientViTSAML0
import ai.nets.samj.communication.model.EfficientViTSAML1
import ai.nets.samj.communication.model.EfficientViTSAML2
import ij.IJ
import ij.ImagePlus
import ij.WindowManager
import ij.gui.PointRoi
import ij.plugin.filter.MaximumFinder
import ij.plugin.frame.RoiManager
import ij.process.ColorProcessor
import ij.process.ShortProcessor
import net.imglib2.img.display.imagej.ImageJFunctions

import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

// Belt-and-braces: force the SAM env root to Fiji/appose_<arch>. SAMJ's
// auto-detection walks up from java.home to find Fiji.App and can fail in some
// launch paths; setting it explicitly is harmless when auto-detection works.
try {
    def fijiFolder = Constants.FIJI_FOLDER
    def arch = System.getProperty("os.arch")
    def suffix = (arch == "aarch64" || arch == "arm64") ? "arm64" : "x86_64"
    def envRoot = fijiFolder + java.io.File.separator + "appose_" + suffix
    SamEnvManagerAbstract.DEFAULT_DIR = envRoot
    IJ.log("[SAMJ HRF] SAM env root: " + envRoot)
} catch (Throwable t) {
    IJ.log("[SAMJ HRF] WARN: could not set SAM env root: " + t.getMessage())
}

// ------------------------------------------------------------------
// 1. sanity: we need an image to work with
// ------------------------------------------------------------------
if (imp == null) {
    JOptionPane.showMessageDialog(null,
        "Open an OCT image or stack in Fiji first, then run this script again.",
        "SAMJ HRF AutoPrompter", JOptionPane.ERROR_MESSAGE)
    return
}

def W = imp.getWidth()
def H = imp.getHeight()
def D = imp.getStackSize()
def sourceTitle = imp.getTitle()

// ------------------------------------------------------------------
// 2. companion 16-bit label stack (same dims), lazy-created once
// ------------------------------------------------------------------
def labelsTitle = sourceTitle.replaceFirst(/\.[^.]+$/, "") + "_HRF_Labels"
def labels = WindowManager.getImage(labelsTitle)
if (labels == null) {
    labels = IJ.createImage(labelsTitle, "16-bit black", W, H, D)
    labels.show()
}

// ------------------------------------------------------------------
// 3. model registry (name -> factory closure)
// ------------------------------------------------------------------
def MODELS = new LinkedHashMap<String, Closure>()
MODELS["SAM2 Tiny (fast, small)"]     = { new SAM2Tiny() }
MODELS["SAM2 Small"]                  = { new SAM2Small() }
MODELS["SAM2 Large (accurate, slow)"] = { new SAM2Large() }
MODELS["EfficientSAM"]                = { new EfficientSAM() }
MODELS["EfficientViTSAM L0"]          = { new EfficientViTSAML0() }
MODELS["EfficientViTSAM L1"]          = { new EfficientViTSAML1() }
MODELS["EfficientViTSAM L2 (paper-recommended CPU pick)"] = { new EfficientViTSAML2() }

// state shared across button handlers
def state = [ model: null, modelName: null ]

// ------------------------------------------------------------------
// 4. helpers (one small job each)
// ------------------------------------------------------------------

// Find bright candidate foci on one slice. Returns a list of {x, y} points.
// tolerance     = MaximumFinder prominence (how much a peak must stand out).
// minBrightness = brightness floor; 0 means "auto" (mean + 2*stdDev of the slice).
// maxFoci       = keep only the brightest this-many foci; 0 means keep all.
def detectFoci = { ip, double tolerance, double minBrightness, int maxFoci ->
    def finder = new MaximumFinder()
    def maxima = finder.getMaxima(ip, tolerance, true)   // excludeOnEdges = true

    double floor = minBrightness
    if (floor <= 0) {
        def stats = ip.getStatistics()
        floor = stats.mean + 2.0 * stats.stdDev
    }

    def foci = new ArrayList<int[]>()
    for (int i = 0; i < maxima.npoints; i++) {
        int x = maxima.xpoints[i]
        int y = maxima.ypoints[i]
        if (ip.getf(x, y) >= floor) foci.add([x, y] as int[])   // getf: raw value, matching the stats above
    }

    if (maxFoci > 0 && foci.size() > maxFoci) {
        foci.sort { a, b -> Double.compare(ip.getf(b[0], b[1]), ip.getf(a[0], a[1])) }
        foci = new ArrayList<int[]>(foci.subList(0, maxFoci))
    }
    return foci
}

// Turn candidate foci into SAMJ prompts. Returns [pointList, rectList].
// A small box centred on each focus tends to tighten SAM onto the focus; a bare
// point lets SAM grab the whole bright structure around it.
def buildPrompts = { List<int[]> foci, boolean useBox, int halfSize ->
    def pointList = new ArrayList<int[]>()
    def rectList = new ArrayList<Rectangle>()
    foci.each { pt ->
        if (useBox) {
            int x0 = Math.max(0, pt[0] - halfSize)
            int y0 = Math.max(0, pt[1] - halfSize)
            int x1 = Math.min(W - 1, pt[0] + halfSize)
            int y1 = Math.min(H - 1, pt[1] + halfSize)
            rectList.add(new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1))
        } else {
            pointList.add([pt[0], pt[1]] as int[])
        }
    }
    return [pointList, rectList]
}

// Segment one slice with the given prompts; returns the mask RAI (or null when
// there are no prompts to run).
def segmentSlice = { int sliceIdx, List<int[]> pointList, List<Rectangle> rectList ->
    if (pointList.isEmpty() && rectList.isEmpty()) return null
    def sliceProc = imp.getStack().getProcessor(sliceIdx).duplicate()
    if (sliceProc instanceof ColorProcessor)
        sliceProc = sliceProc.convertToByteProcessor()   // SAM needs a greyscale (real) slice
    def sliceImp = new ImagePlus("slice_${sliceIdx}", sliceProc)
    def rai = ImageJFunctions.wrapReal(sliceImp)
    return SAMJ_Annotator.samJReturnMask(state.model, rai, pointList, rectList)
}

// Paint every non-zero mask pixel into the label stack at sliceIdx; returns the count.
def paintForeground = { maskRai, int sliceIdx, int labelValue ->
    if (maskRai == null) return 0
    def dstProc = labels.getStack().getProcessor(sliceIdx) as ShortProcessor
    def maskAccess = maskRai.randomAccess()
    def pos = new long[2]
    int painted = 0
    for (int y = 0; y < H; y++) {
        pos[1] = y
        for (int x = 0; x < W; x++) {
            pos[0] = x
            maskAccess.setPosition(pos)
            if (maskAccess.get().getInteger() != 0) {
                dstProc.set(x, y, labelValue)
                painted++
            }
        }
    }
    return painted
}

// Segment a single focus inside a small native-resolution tile around it, and
// paint the result straight into the label stack. Returns the painted count.
//
// SAM rescales its whole input to 1024 px, so on a full B-scan a tiny focus can
// shrink to sub-pixel and disappear. Cropping a small tile keeps the focus large
// in SAM's view. The cost is one segmentation per focus rather than per slice.
// sliceProc is the slice already duplicated (and greyscaled) by the caller, so one focus
// does not re-copy the whole B-scan; tileHalf is the tile half-size in pixels.
def segmentFocusInTile = { sliceProc, int sliceIdx, int[] focus, boolean useBox, int halfSize, int tileHalf, int labelValue ->
    // Tile bounds on the full slice, clamped to the image.
    int x0 = Math.max(0, focus[0] - tileHalf)
    int y0 = Math.max(0, focus[1] - tileHalf)
    int x1 = Math.min(W - 1, focus[0] + tileHalf)
    int y1 = Math.min(H - 1, focus[1] + tileHalf)
    int cw = x1 - x0 + 1
    int ch = y1 - y0 + 1

    sliceProc.setRoi(new Rectangle(x0, y0, cw, ch))
    def cropImp = new ImagePlus("tile", sliceProc.crop())
    def rai = ImageJFunctions.wrapReal(cropImp)

    // One prompt for this focus, in tile-local coordinates.
    int lx = focus[0] - x0
    int ly = focus[1] - y0
    def pointList = new ArrayList<int[]>()
    def rectList = new ArrayList<Rectangle>()
    if (useBox) {
        int bx0 = Math.max(0, lx - halfSize)
        int by0 = Math.max(0, ly - halfSize)
        int bx1 = Math.min(cw - 1, lx + halfSize)
        int by1 = Math.min(ch - 1, ly + halfSize)
        rectList.add(new Rectangle(bx0, by0, bx1 - bx0 + 1, by1 - by0 + 1))
    } else {
        pointList.add([lx, ly] as int[])
    }
    def maskRai = SAMJ_Annotator.samJReturnMask(state.model, rai, pointList, rectList)
    if (maskRai == null) return 0

    // Paint the tile-sized mask back at the tile origin.
    def dstProc = labels.getStack().getProcessor(sliceIdx) as ShortProcessor
    def maskAccess = maskRai.randomAccess()
    def pos = new long[2]
    int painted = 0
    for (int y = 0; y < ch; y++) {
        pos[1] = y
        for (int x = 0; x < cw; x++) {
            pos[0] = x
            maskAccess.setPosition(pos)
            if (maskAccess.get().getInteger() != 0) {
                dstProc.set(x0 + x, y0 + y, labelValue)
                painted++
            }
        }
    }
    return painted
}

// Drop the detected foci onto the source image's ROI Manager as one PointRoi,
// so the user can see where the script prompted on each slice.
def addPointsToRoiManager = { List<int[]> foci, int sliceIdx ->
    if (foci.isEmpty()) return
    int n = foci.size()
    def xs = new int[n]
    def ys = new int[n]
    foci.eachWithIndex { pt, i -> xs[i] = pt[0]; ys[i] = pt[1] }
    def proi = new PointRoi(xs, ys, n)
    proi.setPosition(sliceIdx)
    // The ROI Manager is a Swing window, so touch it only on the event thread.
    SwingUtilities.invokeLater {
        RoiManager.getRoiManager().add(imp, proi, sliceIdx)
    }
}

// ------------------------------------------------------------------
// 5. UI
// ------------------------------------------------------------------
def frame = new JFrame("SAMJ HRF AutoPrompter — " + sourceTitle)
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)

def panel = new JPanel()
panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))

// Row: model
def modelRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
modelRow.add(new JLabel("Model:"))
def modelCombo = new JComboBox<>(MODELS.keySet() as String[])
modelCombo.setSelectedItem("SAM2 Tiny (fast, small)")
modelRow.add(modelCombo)
def loadBtn = new JButton("Load model")
modelRow.add(loadBtn)
panel.add(modelRow)

// Row: scope
def scopeRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
scopeRow.add(new JLabel("Scope:"))
def scopeCombo = new JComboBox<>(["Current slice", "Whole stack"] as String[])
if (D < 2) scopeCombo.setSelectedItem("Current slice")
scopeRow.add(scopeCombo)
panel.add(scopeRow)

// Row: detector
def detectRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
detectRow.add(new JLabel("Prominence:"))
def tolSpinner = new JSpinner(new SpinnerNumberModel(25.0d, 0.0d, 65535.0d, 5.0d))
tolSpinner.setPreferredSize(new Dimension(80, 24))
detectRow.add(tolSpinner)
detectRow.add(new JLabel("Min brightness (0 = auto):"))
def brightSpinner = new JSpinner(new SpinnerNumberModel(0.0d, 0.0d, 65535.0d, 5.0d))
brightSpinner.setPreferredSize(new Dimension(80, 24))
detectRow.add(brightSpinner)
detectRow.add(new JLabel("Max foci/slice (0 = all):"))
def maxFociSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1))
maxFociSpinner.setPreferredSize(new Dimension(70, 24))
detectRow.add(maxFociSpinner)
panel.add(detectRow)

// Row: prompt shape
def promptRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
promptRow.add(new JLabel("Prompt:"))
def promptCombo = new JComboBox<>(["Box (tighter)", "Point"] as String[])
promptRow.add(promptCombo)
promptRow.add(new JLabel("Box half-size px:"))
def halfSpinner = new JSpinner(new SpinnerNumberModel(6, 1, 200, 1))
halfSpinner.setPreferredSize(new Dimension(70, 24))
promptRow.add(halfSpinner)
panel.add(promptRow)

// Row: tiling (keeps tiny foci from vanishing under SAM's 1024 px rescaling)
def tileRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
def useTilesCheck = new JCheckBox("Segment in a tile around each focus (best for tiny foci)", false)
tileRow.add(useTilesCheck)
tileRow.add(new JLabel("Tile half-size px:"))
def tileSpinner = new JSpinner(new SpinnerNumberModel(96, 16, 1024, 16))
tileSpinner.setPreferredSize(new Dimension(80, 24))
tileRow.add(tileSpinner)
panel.add(tileRow)

// Row: label + show points
def labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
labelRow.add(new JLabel("Label ID for HRF:"))
def labelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 65535, 1))
labelSpinner.setPreferredSize(new Dimension(80, 24))
labelRow.add(labelSpinner)
def showPoints = new JCheckBox("add detected points to ROI Manager", true)
labelRow.add(showPoints)
panel.add(labelRow)

// Row: run
def runBtn = new JButton("Detect HRF and segment")
runBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
runBtn.setEnabled(false)
panel.add(runBtn)

// Row: save
def saveBtn = new JButton("Save HRF labels as TIFF…")
saveBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
panel.add(saveBtn)

// Row: status
def status = new JLabel("Load a model to begin.")
status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0))
panel.add(status)

frame.add(panel, BorderLayout.CENTER)
frame.pack()
frame.setLocationRelativeTo(null)
frame.setVisible(true)

// release the model process on window close so the Python worker doesn't linger
frame.addWindowListener(new WindowAdapter() {
    @Override
    void windowClosed(WindowEvent e) {
        try {
            if (state.model != null) state.model.closeProcess()
        } catch (Throwable t) { /* ignore */ }
    }
})

// ------------------------------------------------------------------
// 6. handlers
// ------------------------------------------------------------------
loadBtn.addActionListener({ e ->
    def name = modelCombo.getSelectedItem() as String
    status.setText("Loading ${name} … (first-time model install can take several minutes)")
    loadBtn.setEnabled(false)
    Thread.start {
        try {
            if (state.model != null) {
                try { state.model.closeProcess() } catch (Throwable t) {}
            }
            state.model = MODELS[name].call()
            state.modelName = name
            SwingUtilities.invokeLater {
                status.setText("${name} ready. Choose a scope and press Detect HRF and segment.")
                runBtn.setEnabled(true)
                loadBtn.setEnabled(true)
            }
        } catch (Throwable ex) {
            ex.printStackTrace()
            SwingUtilities.invokeLater {
                status.setText("Load failed: ${ex.getClass().getSimpleName()}: ${ex.getMessage()}")
                loadBtn.setEnabled(true)
            }
        }
    }
} as ActionListener)

runBtn.addActionListener({ e ->
    double tolerance = (tolSpinner.getValue() as Number).doubleValue()
    double minBrightness = (brightSpinner.getValue() as Number).doubleValue()
    int maxFoci = (maxFociSpinner.getValue() as Integer).intValue()
    boolean useBox = (promptCombo.getSelectedItem() as String).startsWith("Box")
    int halfSize = (halfSpinner.getValue() as Integer).intValue()
    int labelValue = (labelSpinner.getValue() as Integer).intValue()
    boolean wholeStack = (scopeCombo.getSelectedItem() as String) == "Whole stack"
    boolean markPoints = showPoints.isSelected()
    boolean useTiles = useTilesCheck.isSelected()
    int tileHalf = (tileSpinner.getValue() as Integer).intValue()

    def sliceRange = wholeStack ? (1..D) : [imp.getCurrentSlice()]

    runBtn.setEnabled(false)
    loadBtn.setEnabled(false)
    status.setText("Detecting and segmenting HRF over ${sliceRange.size()} slice(s) …")

    Thread.start {
        try {
            int totalFoci = 0
            int totalPainted = 0
            int slicesWithFoci = 0
            for (int sliceIdx : sliceRange) {
                def ip = imp.getStack().getProcessor(sliceIdx)
                def foci = detectFoci(ip, tolerance, minBrightness, maxFoci)
                if (foci.isEmpty()) continue
                slicesWithFoci++
                totalFoci += foci.size()

                if (useTiles) {
                    // One native-resolution tile per focus, cropped from a single
                    // greyscale copy of this slice.
                    def tileSource = imp.getStack().getProcessor(sliceIdx).duplicate()
                    if (tileSource instanceof ColorProcessor) tileSource = tileSource.convertToByteProcessor()
                    foci.each { focus ->
                        totalPainted += segmentFocusInTile(tileSource, sliceIdx, focus, useBox, halfSize, tileHalf, labelValue)
                    }
                } else {
                    // One segmentation for the whole slice with all prompts.
                    def prompts = buildPrompts(foci, useBox, halfSize)
                    def maskRai = segmentSlice(sliceIdx, prompts[0], prompts[1])
                    totalPainted += paintForeground(maskRai, sliceIdx, labelValue)
                }
                if (markPoints) addPointsToRoiManager(foci, sliceIdx)

                final int shownSlice = sliceIdx
                final int shownFoci = totalFoci
                SwingUtilities.invokeLater {
                    status.setText("Slice ${shownSlice}: ${shownFoci} foci so far …")
                }
            }

            final int fFoci = totalFoci
            final int fPainted = totalPainted
            final int fSlices = slicesWithFoci
            SwingUtilities.invokeLater {
                labels.updateAndDraw()
                status.setText("Done. ${fFoci} HRF candidates on ${fSlices} slice(s); painted ${fPainted} px with label ${labelValue}.")
                runBtn.setEnabled(true)
                loadBtn.setEnabled(true)
            }
        } catch (Throwable ex) {
            ex.printStackTrace()
            SwingUtilities.invokeLater {
                status.setText("Run failed: ${ex.getClass().getSimpleName()}: ${ex.getMessage()}")
                runBtn.setEnabled(true)
                loadBtn.setEnabled(true)
            }
        }
    }
} as ActionListener)

saveBtn.addActionListener({ e ->
    def chooser = new JFileChooser()
    def defName = sourceTitle.replaceFirst(/\.[^.]+$/, "") + "_HRF_Labels.tif"
    chooser.setSelectedFile(new File(System.getProperty("user.home"), defName))
    if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
        def path = chooser.getSelectedFile().getAbsolutePath()
        if (!path.toLowerCase().endsWith(".tif") && !path.toLowerCase().endsWith(".tiff")) path += ".tif"
        IJ.saveAsTiff(labels, path)
        status.setText("Saved: ${path}")
    }
} as ActionListener)
