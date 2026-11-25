#@ ImagePlus imp
/*
 * SAMJ HRF AutoPrompter (Groovy script)
 *
 * Segment Hyperreflective Foci (HRF) in OCT images WITHOUT placing a prompt by
 * hand on every focus. A TRAINED HRF detector (a U-Net, smp ResNet-34, trained
 * on OCT HRF) locates the foci, and its predicted boxes are fed to SAMJ's SAM as
 * prompts. This replaces the earlier brightness/prominence detector, which on
 * real OCT locked onto the bright retinal band and missed the actual foci.
 *
 * How to use:
 *   1. Open your OCT image or stack in Fiji (a single B-scan or a whole volume).
 *   2. Run this script: Plugins > SAMJ > SAMJ HRF AutoPrompter.
 *   3. Pick a SAM model and click Load model (only slow the first time).
 *   4. Choose the scope (current slice or whole stack) and the detector threshold
 *      (lower = higher recall), then click "Detect HRF and segment". Per slice it:
 *        a. runs the trained detector (hrf_detector.py) to get HRF boxes,
 *        b. feeds those boxes to SAMJ as prompts,
 *        c. paints the returned masks into a companion label stack
 *           ("<title>_HRF_Labels") and drops the detected boxes into the ROI
 *           Manager so you can see what the detector found.
 *   5. Click "Save HRF labels" when finished.
 *
 * The detector runs in SAMJ's own Python environment (the Appose env that already
 * has PyTorch), exactly like the SAM model does. It needs the trained weights
 * file; by default this looks for "samj_hrf_detector.pt" in the Fiji folder (the
 * same place SAMJ keeps its models). Point the "Weights" box at your own file if
 * it lives elsewhere. The first run installs segmentation-models-pytorch into the
 * env, which is slow once.
 *
 * Boundary quality for small foci: SAM rescales its input to 1024 px, which can
 * shrink a tiny focus to sub-pixel on a full B-scan. Tick "Segment in a tile
 * around each focus" to run SAM on a small native-resolution crop around each
 * detection instead, which keeps the focus large in SAM's view.
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
import ij.gui.Roi
import ij.plugin.frame.RoiManager
import ij.process.ColorProcessor
import ij.process.ShortProcessor
import net.imglib2.img.display.imagej.ImageJFunctions
import groovy.json.JsonSlurper

import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File

// Belt-and-braces: force the SAM env root to Fiji/appose_<arch>.
def archSuffix = (System.getProperty("os.arch") in ["aarch64", "arm64"]) ? "arm64" : "x86_64"
try {
    SamEnvManagerAbstract.DEFAULT_DIR = Constants.FIJI_FOLDER + File.separator + "appose_" + archSuffix
} catch (Throwable t) {
    IJ.log("[SAMJ HRF] WARN: could not set SAM env root: " + t.getMessage())
}

// ------------------------------------------------------------------
// 1. sanity
// ------------------------------------------------------------------
if (imp == null) {
    JOptionPane.showMessageDialog(null,
        "Open an OCT image or stack in Fiji first, then run this script again.",
        "SAMJ HRF AutoPrompter", JOptionPane.ERROR_MESSAGE)
    return
}
def W = imp.getWidth(); def H = imp.getHeight(); def D = imp.getStackSize()
def sourceTitle = imp.getTitle()

// ------------------------------------------------------------------
// 2. companion label stack
// ------------------------------------------------------------------
def labelsTitle = sourceTitle.replaceFirst(/\.[^.]+$/, "") + "_HRF_Labels"
def labels = WindowManager.getImage(labelsTitle)
if (labels == null) { labels = IJ.createImage(labelsTitle, "16-bit black", W, H, D); labels.show() }

// ------------------------------------------------------------------
// 3. model registry + shared state (SAM variant, detector locations)
// ------------------------------------------------------------------
def MODELS = new LinkedHashMap<String, Closure>()
MODELS["SAM2 Tiny (fast, small)"]     = { new SAM2Tiny() }
MODELS["SAM2 Small"]                  = { new SAM2Small() }
MODELS["SAM2 Large (accurate, slow)"] = { new SAM2Large() }
MODELS["EfficientSAM"]                = { new EfficientSAM() }
MODELS["EfficientViTSAM L0"]          = { new EfficientViTSAML0() }
MODELS["EfficientViTSAM L1"]          = { new EfficientViTSAML1() }
MODELS["EfficientViTSAM L2 (paper-recommended CPU pick)"] = { new EfficientViTSAML2() }

def envRoot = Constants.FIJI_FOLDER + File.separator + "appose_" + archSuffix
def state = [
    model: null,
    python: envRoot + "/envs/sam2/bin/python",
    detector: Constants.FIJI_FOLDER + "/scripts/Plugins/SAMJ/hrf_detector.py",
    weights: Constants.FIJI_FOLDER + "/samj_hrf_detector.pt",
]

// ------------------------------------------------------------------
// 4. helpers (one small job each)
// ------------------------------------------------------------------

// Run the trained detector on one slice and return its HRF boxes.
def detectBoxes = { int sliceIdx, double threshold ->
    def ip = imp.getStack().getProcessor(sliceIdx)
    def tiff = File.createTempFile("samj_slice_", ".tif")
    def outJson = File.createTempFile("samj_boxes_", ".json")
    IJ.saveAsTiff(new ImagePlus("slice", ip.duplicate()), tiff.getAbsolutePath())
    def cmd = [state.python, state.detector, tiff.getAbsolutePath(),
               state.weights, outJson.getAbsolutePath(), threshold.toString()]
    def pb = new ProcessBuilder(cmd as String[]); pb.redirectErrorStream(true)
    def proc = pb.start(); def log = proc.getInputStream().getText("UTF-8"); proc.waitFor()
    if (proc.exitValue() != 0)
        throw new RuntimeException("detector failed:\n" + log)
    def found = new JsonSlurper().parse(outJson)
    tiff.delete(); outJson.delete()
    return found.collect { new Rectangle(it.x as int, it.y as int, it.w as int, it.h as int) }
}

// Wrap slice sliceIdx (1-based) as a 2D ImgLib2 image; colour is greyscaled.
def extractSliceRai = { int sliceIdx ->
    def sliceProc = imp.getStack().getProcessor(sliceIdx).duplicate()
    if (sliceProc instanceof ColorProcessor) sliceProc = sliceProc.convertToByteProcessor()
    return ImageJFunctions.wrapReal(new ImagePlus("slice_${sliceIdx}", sliceProc))
}

// Segment one slice from the given box prompts; returns the mask RAI (or null).
def segmentBoxes = { int sliceIdx, List<Rectangle> rects ->
    if (rects.isEmpty()) return null
    def rl = new ArrayList<Rectangle>(rects)
    return SAMJ_Annotator.samJReturnMask(state.model, extractSliceRai(sliceIdx), new ArrayList<int[]>(), rl)
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
            if (maskAccess.get().getInteger() != 0) { dstProc.set(x, y, labelValue); painted++ }
        }
    }
    return painted
}

// Add the detector's boxes to the ROI Manager (on the event thread) so the user
// can see exactly what was found and prompted.
def addBoxesToRoiManager = { List<Rectangle> rects, int sliceIdx ->
    if (rects.isEmpty()) return
    SwingUtilities.invokeLater {
        def rm = RoiManager.getRoiManager()
        rects.each { r -> def roi = new Roi(r); roi.setPosition(sliceIdx); rm.add(imp, roi, sliceIdx) }
    }
}

// ------------------------------------------------------------------
// 5. UI
// ------------------------------------------------------------------
def frame = new JFrame("SAMJ HRF AutoPrompter — " + sourceTitle)
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)
def panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))

def modelRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
modelRow.add(new JLabel("SAM model:"))
def modelCombo = new JComboBox<>(MODELS.keySet() as String[])
modelCombo.setSelectedItem("SAM2 Tiny (fast, small)")
modelRow.add(modelCombo)
def loadBtn = new JButton("Load model"); modelRow.add(loadBtn)
panel.add(modelRow)

def weightsRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
weightsRow.add(new JLabel("Detector weights:"))
def weightsField = new JTextField(state.weights, 32)
weightsRow.add(weightsField)
panel.add(weightsRow)

def optRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
optRow.add(new JLabel("Scope:"))
def scopeCombo = new JComboBox<>(["Current slice", "Whole stack"] as String[])
optRow.add(scopeCombo)
optRow.add(new JLabel("Detector threshold:"))
def thrSpinner = new JSpinner(new SpinnerNumberModel(0.30d, 0.05d, 0.95d, 0.05d))
thrSpinner.setPreferredSize(new Dimension(70, 24)); optRow.add(thrSpinner)
def tileCheck = new JCheckBox("tile around each focus", false); optRow.add(tileCheck)
panel.add(optRow)

def labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
labelRow.add(new JLabel("Label ID for HRF:"))
def labelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 65535, 1))
labelSpinner.setPreferredSize(new Dimension(80, 24)); labelRow.add(labelSpinner)
panel.add(labelRow)

def runBtn = new JButton("Detect HRF and segment"); runBtn.setEnabled(false); panel.add(runBtn)
def saveBtn = new JButton("Save HRF labels as TIFF…"); panel.add(saveBtn)
def status = new JLabel("Load a model to begin.")
status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0)); panel.add(status)

frame.add(panel, BorderLayout.CENTER); frame.pack(); frame.setLocationRelativeTo(null); frame.setVisible(true)
frame.addWindowListener(new WindowAdapter() {
    @Override
    void windowClosed(WindowEvent e) {
        try { if (state.model != null) state.model.closeProcess() } catch (Throwable t) {}
    }
})

// ------------------------------------------------------------------
// 6. the tile helper (native-resolution crop around one detection)
// ------------------------------------------------------------------
def TILE = 96
def segmentFocusInTile = { sliceProc, int sliceIdx, Rectangle box, int labelValue ->
    int cx = box.x + box.width / 2 as int; int cy = box.y + box.height / 2 as int
    int x0 = Math.max(0, cx - TILE); int y0 = Math.max(0, cy - TILE)
    int x1 = Math.min(W - 1, cx + TILE); int y1 = Math.min(H - 1, cy + TILE)
    int cw = x1 - x0 + 1; int ch = y1 - y0 + 1
    sliceProc.setRoi(new Rectangle(x0, y0, cw, ch))
    def cropRai = ImageJFunctions.wrapReal(new ImagePlus("tile", sliceProc.crop()))
    int lx = Math.max(0, box.x - x0); int ly = Math.max(0, box.y - y0)
    int lw = Math.min(cw - lx, box.width); int lh = Math.min(ch - ly, box.height)
    def rl = new ArrayList<Rectangle>(); rl.add(new Rectangle(lx, ly, lw, lh))
    def mask = SAMJ_Annotator.samJReturnMask(state.model, cropRai, new ArrayList<int[]>(), rl)
    if (mask == null) return 0
    def dstProc = labels.getStack().getProcessor(sliceIdx) as ShortProcessor
    def maskAccess = mask.randomAccess(); def pos = new long[2]; int painted = 0
    for (int y = 0; y < ch; y++) {
        pos[1] = y
        for (int x = 0; x < cw; x++) {
            pos[0] = x; maskAccess.setPosition(pos)
            if (maskAccess.get().getInteger() != 0) { dstProc.set(x0 + x, y0 + y, labelValue); painted++ }
        }
    }
    return painted
}

// ------------------------------------------------------------------
// 7. handlers
// ------------------------------------------------------------------
loadBtn.addActionListener({ e ->
    def name = modelCombo.getSelectedItem() as String
    status.setText("Loading ${name} … (first-time model install can take several minutes)")
    loadBtn.setEnabled(false)
    Thread.start {
        try {
            if (state.model != null) { try { state.model.closeProcess() } catch (Throwable t) {} }
            state.model = MODELS[name].call()
            SwingUtilities.invokeLater {
                status.setText("${name} ready. Choose a scope and press Detect HRF and segment.")
                runBtn.setEnabled(true); loadBtn.setEnabled(true)
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
    state.weights = weightsField.getText().trim()
    double threshold = (thrSpinner.getValue() as Number).doubleValue()
    int labelValue = (labelSpinner.getValue() as Integer).intValue()
    boolean wholeStack = (scopeCombo.getSelectedItem() as String) == "Whole stack"
    boolean useTiles = tileCheck.isSelected()
    def sliceRange = wholeStack ? (1..D) : [imp.getCurrentSlice()]

    if (!new File(state.python).exists()) {
        status.setText("No SAM Python env yet — load a SAM model once so SAMJ sets it up."); return
    }
    if (!new File(state.weights).exists()) {
        status.setText("Detector weights not found at ${state.weights} — set the Weights box to your .pt file."); return
    }
    runBtn.setEnabled(false); loadBtn.setEnabled(false)
    status.setText("Detecting and segmenting HRF over ${sliceRange.size()} slice(s) … (first run installs the detector deps)")

    Thread.start {
        try {
            int totalBoxes = 0; int totalPainted = 0; int slicesWithHrf = 0
            for (int sliceIdx : sliceRange) {
                def boxes = detectBoxes(sliceIdx, threshold)
                if (boxes.isEmpty()) continue
                slicesWithHrf++; totalBoxes += boxes.size()
                if (useTiles) {
                    def src = imp.getStack().getProcessor(sliceIdx).duplicate()
                    if (src instanceof ColorProcessor) src = src.convertToByteProcessor()
                    boxes.each { b -> totalPainted += segmentFocusInTile(src, sliceIdx, b, labelValue) }
                } else {
                    totalPainted += paintForeground(segmentBoxes(sliceIdx, boxes), sliceIdx, labelValue)
                }
                addBoxesToRoiManager(boxes, sliceIdx)
                final int sShown = sliceIdx; final int bShown = totalBoxes
                SwingUtilities.invokeLater { status.setText("Slice ${sShown}: ${bShown} HRF so far …") }
            }
            final int fBoxes = totalBoxes; final int fPainted = totalPainted; final int fSlices = slicesWithHrf
            SwingUtilities.invokeLater {
                labels.updateAndDraw()
                status.setText("Done. ${fBoxes} HRF detected on ${fSlices} slice(s); painted ${fPainted} px with label ${labelValue}.")
                runBtn.setEnabled(true); loadBtn.setEnabled(true)
            }
        } catch (Throwable ex) {
            ex.printStackTrace()
            SwingUtilities.invokeLater {
                status.setText("Run failed: ${ex.getClass().getSimpleName()}: ${ex.getMessage()}")
                runBtn.setEnabled(true); loadBtn.setEnabled(true)
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
        IJ.saveAsTiff(labels, path); status.setText("Saved: ${path}")
    }
} as ActionListener)
