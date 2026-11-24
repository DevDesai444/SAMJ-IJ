#@ ImagePlus imp
/*
 * SAMJ 3D Annotator (Groovy script)
 *
 * Annotate a 3D stack slice by slice without ever leaving the volume window:
 *   1. Open your 3D stack in Fiji (greyscale; colour slices are converted to greyscale).
 *   2. Run this script: Plugins > SAMJ > SAMJ 3D Annotator.
 *   3. Pick a SAM model in the panel, click Load model (only slow the first time).
 *   4. Navigate to any slice on your 3D window and draw a rectangle ROI.
 *   5a. Click "Segment current slice" -- the resulting binary mask is painted
 *      into a companion 3D label stack ("<title>_SAMJ_3D_Labels") at that Z,
 *      using the label ID from the spinner. Advance the slice slider, repeat.
 *   5b. Or click "Propagate active ROI through the stack" -- this segments the
 *      current slice, then carries the mask from slice to slice automatically
 *      (forward and, optionally, backward), using the previous slice's mask
 *      bounding box as the box prompt for the next slice. It stops at the stack
 *      end, at the "Max slices" limit, or as soon as a slice comes back empty.
 *      One click annotates a whole structure through depth, so you no longer
 *      have to draw a prompt on every single B-scan.
 *   6. Click "Save 3D labels" when finished (or File > Save As on the labels window).
 *
 * Note on propagation: SAM2's internal video memory is not exposed through
 * SAMJ's Java API, so this carries the mask forward by re-prompting each slice
 * with the previous slice's bounding box. It is simple and robust, and works
 * with every SAM variant SAMJ offers.
 *
 * The companion label stack is a normal 16-bit ImagePlus you can open again,
 * feed to Labkit as an existing labeling, or use for training.
 *
 * Under the hood: this script calls SAMJ's public static Java API
 * ai.nets.samj.ij.SAMJ_Annotator.samJReturnMask(model, slice_rai, points, rects)
 * per slice. There is no Labkit dependency and no need to duplicate slices.
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
import ij.process.ColorProcessor
import ij.process.ShortProcessor
import net.imglib2.img.display.imagej.ImageJFunctions

// Belt-and-braces: force the SAM env root to Fiji/appose_arm64 (or the
// arch-appropriate name). SAMJ's auto-detection walks up from java.home to
// find Fiji.App and can fail in some launch paths; setting it explicitly is
// harmless when auto-detection would have worked anyway.
try {
    def fijiFolder = Constants.FIJI_FOLDER  // computed at class-load time
    def arch = System.getProperty("os.arch")
    def suffix = (arch == "aarch64" || arch == "arm64") ? "arm64" : "x86_64"
    def envRoot = fijiFolder + java.io.File.separator + "appose_" + suffix
    SamEnvManagerAbstract.DEFAULT_DIR = envRoot
    IJ.log("[SAMJ 3D] SAM env root: " + envRoot)
} catch (Throwable t) {
    IJ.log("[SAMJ 3D] WARN: could not set SAM env root: " + t.getMessage())
}

import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionListener
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

// ------------------------------------------------------------------
// 1. sanity: we need a stack to work with
// ------------------------------------------------------------------
if (imp == null) {
    JOptionPane.showMessageDialog(null,
        "Open a 3D stack in Fiji first, then run this script again.",
        "SAMJ 3D Annotator", JOptionPane.ERROR_MESSAGE)
    return
}
if (imp.getStackSize() < 2) {
    JOptionPane.showMessageDialog(null,
        "Active image is single-slice (${imp.getStackSize()} frame).\n" +
        "For 2D annotation use Plugins > SAMJ > SAMJ Annotator.\n" +
        "For 3D, open a stack first (Image > Stacks > Tools > ...).",
        "SAMJ 3D Annotator", JOptionPane.WARNING_MESSAGE)
    return
}

def W = imp.getWidth()
def H = imp.getHeight()
def D = imp.getStackSize()
def sourceTitle = imp.getTitle()

// ------------------------------------------------------------------
// 2. companion 16-bit label stack (same dims), lazy-created once
// ------------------------------------------------------------------
def labelsTitle = sourceTitle.replaceFirst(/\.[^.]+$/, "") + "_SAMJ_3D_Labels"
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
// 3b. small helpers shared by the single-slice and propagation paths
// ------------------------------------------------------------------

// Wrap slice sliceIdx (1-based) of the source stack as a 2D ImgLib2 image. A colour
// slice is converted to greyscale first, because SAM works on a real-valued image.
def extractSliceRai = { int sliceIdx ->
    def sliceProc = imp.getStack().getProcessor(sliceIdx).duplicate()
    if (sliceProc instanceof ColorProcessor)
        sliceProc = sliceProc.convertToByteProcessor()
    def sliceImp = new ImagePlus("slice_${sliceIdx}", sliceProc)
    return ImageJFunctions.wrapReal(sliceImp)
}

// Segment one slice with a single rectangle prompt; returns the mask RAI.
def segmentRect = { int sliceIdx, java.awt.Rectangle rect ->
    def rai = extractSliceRai(sliceIdx)
    def rectList = new ArrayList<java.awt.Rectangle>()
    rectList.add(rect)
    def pointList = new ArrayList<int[]>()
    return SAMJ_Annotator.samJReturnMask(state.model, rai, pointList, rectList)
}

// Paint every non-zero mask pixel into the label stack at sliceIdx, in one pass, and
// measure the mask at the same time. Returns [painted pixel count, tight bounding box],
// where the box is null when the mask is empty. (The mask is the same W x H as the slice.)
def paintMask = { maskRai, int sliceIdx, int labelValue ->
    def dstProc = labels.getStack().getProcessor(sliceIdx) as ShortProcessor
    def maskAccess = maskRai.randomAccess()
    def pos = new long[2]
    int painted = 0
    int minX = W, minY = H, maxX = -1, maxY = -1
    for (int y = 0; y < H; y++) {
        pos[1] = y
        for (int x = 0; x < W; x++) {
            pos[0] = x
            maskAccess.setPosition(pos)
            if (maskAccess.get().getInteger() != 0) {
                dstProc.set(x, y, labelValue)
                painted++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    def box = (maxX < 0) ? null : new java.awt.Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
    return [painted, box]
}

// Grow a rectangle by a fraction on each side and clamp it to the image bounds.
def expandRect = { java.awt.Rectangle r, double marginFraction ->
    int growX = (int) Math.round(r.width * marginFraction)
    int growY = (int) Math.round(r.height * marginFraction)
    int x0 = Math.max(0, r.x - growX)
    int y0 = Math.max(0, r.y - growY)
    int x1 = Math.min(W - 1, r.x + r.width - 1 + growX)
    int y1 = Math.min(H - 1, r.y + r.height - 1 + growY)
    return new java.awt.Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1)
}

// Walk one direction (step = +1 forward, -1 backward) carrying the previous mask's
// bounding box as the next box prompt. Stops at the stack end, at maxSlices, or as
// soon as a slice comes back empty. Returns the number of slices painted.
def propagateFrom = { int fromSlice, int step, java.awt.Rectangle fromBox,
                      int labelValue, double marginFraction, int maxSlices ->
    int painted = 0
    def box = fromBox
    int slice = fromSlice + step
    int walked = 0
    while (slice >= 1 && slice <= D && box != null) {
        if (maxSlices > 0 && walked >= maxSlices) break
        def prompt = expandRect(box, marginFraction)
        def mask = segmentRect(slice, prompt)
        def newBox = paintMask(mask, slice, labelValue)[1]
        if (newBox == null) break   // nothing found on this slice: stop this direction
        box = newBox
        painted++
        walked++
        slice += step
    }
    return painted
}

// ------------------------------------------------------------------
// 4. UI
// ------------------------------------------------------------------
def frame = new JFrame("SAMJ 3D Annotator — " + sourceTitle)
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

// Row: label ID
def labelRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
labelRow.add(new JLabel("Label ID for next mask:"))
def labelSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 65535, 1))
labelSpinner.setPreferredSize(new Dimension(80, 24))
labelRow.add(labelSpinner)
def autoIncr = new JCheckBox("auto-increment per segment", false)
labelRow.add(autoIncr)
panel.add(labelRow)

// Row: segment
def segBtn = new JButton("Segment current slice with active ROI")
segBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
segBtn.setEnabled(false)
panel.add(segBtn)

// Row: propagation options
def propRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
propRow.add(new JLabel("Box margin %:"))
def marginSpinner = new JSpinner(new SpinnerNumberModel(10, 0, 100, 5))
marginSpinner.setPreferredSize(new Dimension(70, 24))
propRow.add(marginSpinner)
propRow.add(new JLabel("Max slices (0 = to end):"))
def maxSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 1))
maxSpinner.setPreferredSize(new Dimension(80, 24))
propRow.add(maxSpinner)
def backwardsCheck = new JCheckBox("also propagate backwards", true)
propRow.add(backwardsCheck)
panel.add(propRow)

// Row: propagate
def propBtn = new JButton("Propagate active ROI through the stack")
propBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
propBtn.setEnabled(false)
panel.add(propBtn)

// Row: save
def saveBtn = new JButton("Save 3D labels as TIFF…")
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

// release the model process on window close so Python worker doesn't linger
frame.addWindowListener(new WindowAdapter() {
    @Override
    void windowClosed(WindowEvent e) {
        try {
            if (state.model != null) state.model.closeProcess()
        } catch (Throwable t) { /* ignore */ }
    }
})

// ------------------------------------------------------------------
// 5. handlers
// ------------------------------------------------------------------
loadBtn.addActionListener({ e ->
    def name = modelCombo.getSelectedItem() as String
    status.setText("Loading ${name} … (first-time model install can take several minutes)")
    loadBtn.setEnabled(false)
    Thread.start {
        try {
            // close any previously loaded model
            if (state.model != null) {
                try { state.model.closeProcess() } catch (Throwable t) {}
            }
            state.model = MODELS[name].call()
            state.modelName = name
            SwingUtilities.invokeLater {
                status.setText("${name} ready. On your ${sourceTitle} window: pick a slice, draw a rectangle, then Segment or Propagate.")
                segBtn.setEnabled(true)
                propBtn.setEnabled(true)
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

segBtn.addActionListener({ e ->
    def roi = imp.getRoi()  // SAMJ works from the source window, not any duplicate
    if (roi == null) {
        status.setText("Draw a rectangle ROI on the ${sourceTitle} window first.")
        return
    }
    def bounds = roi.getBounds()
    def rect = new java.awt.Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
    def sliceIdx = imp.getCurrentSlice()  // 1-based
    def labelValue = (labelSpinner.getValue() as Integer).intValue()

    segBtn.setEnabled(false)
    propBtn.setEnabled(false)
    loadBtn.setEnabled(false)
    status.setText("Segmenting slice ${sliceIdx} with rectangle ${bounds.width}x${bounds.height} …")

    Thread.start {
        try {
            def maskRai = segmentRect(sliceIdx, rect)
            int painted = paintMask(maskRai, sliceIdx, labelValue)[0]
            labels.setSlice(sliceIdx)
            labels.updateAndDraw()

            SwingUtilities.invokeLater {
                if (autoIncr.isSelected()) labelSpinner.setValue(labelValue + 1)
                status.setText("Slice ${sliceIdx}: painted ${painted} px with label ${labelValue}. Advance a slice and draw another ROI.")
                segBtn.setEnabled(true)
                propBtn.setEnabled(true)
                loadBtn.setEnabled(true)
            }
        } catch (Throwable ex) {
            ex.printStackTrace()
            SwingUtilities.invokeLater {
                status.setText("Segment failed on slice ${sliceIdx}: ${ex.getClass().getSimpleName()}: ${ex.getMessage()}")
                segBtn.setEnabled(true)
                propBtn.setEnabled(true)
                loadBtn.setEnabled(true)
            }
        }
    }
} as ActionListener)

propBtn.addActionListener({ e ->
    def roi = imp.getRoi()
    if (roi == null) {
        status.setText("Draw a rectangle ROI on the ${sourceTitle} window first, then press Propagate.")
        return
    }
    def bounds = roi.getBounds()
    def seedRect = new java.awt.Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
    def startSlice = imp.getCurrentSlice()
    def labelValue = (labelSpinner.getValue() as Integer).intValue()
    def marginFraction = (marginSpinner.getValue() as Integer).intValue() / 100.0
    def maxSlices = (maxSpinner.getValue() as Integer).intValue()
    def goBackwards = backwardsCheck.isSelected()

    segBtn.setEnabled(false)
    propBtn.setEnabled(false)
    loadBtn.setEnabled(false)
    status.setText("Propagating from slice ${startSlice} … (one segmentation per slice)")

    Thread.start {
        try {
            // Seed slice: segment the ROI the user drew and remember its mask box.
            def seedMask = segmentRect(startSlice, seedRect)
            def seedBox = paintMask(seedMask, startSlice, labelValue)[1]

            int forwardCount = 0
            int backwardCount = 0
            if (seedBox != null) {
                forwardCount = propagateFrom(startSlice, +1, seedBox, labelValue, marginFraction, maxSlices)
                if (goBackwards)
                    backwardCount = propagateFrom(startSlice, -1, seedBox, labelValue, marginFraction, maxSlices)
            }
            int total = 1 + forwardCount + backwardCount

            SwingUtilities.invokeLater {
                if (autoIncr.isSelected()) labelSpinner.setValue(labelValue + 1)
                labels.setSlice(startSlice)
                labels.updateAndDraw()
                status.setText("Propagated label ${labelValue} across ${total} slices (seed ${startSlice}, +${forwardCount} forward, +${backwardCount} backward).")
                segBtn.setEnabled(true)
                propBtn.setEnabled(true)
                loadBtn.setEnabled(true)
            }
        } catch (Throwable ex) {
            ex.printStackTrace()
            SwingUtilities.invokeLater {
                status.setText("Propagation failed: ${ex.getClass().getSimpleName()}: ${ex.getMessage()}")
                segBtn.setEnabled(true)
                propBtn.setEnabled(true)
                loadBtn.setEnabled(true)
            }
        }
    }
} as ActionListener)

saveBtn.addActionListener({ e ->
    def chooser = new JFileChooser()
    def defName = sourceTitle.replaceFirst(/\.[^.]+$/, "") + "_SAMJ_3D_Labels.tif"
    chooser.setSelectedFile(new File(System.getProperty("user.home"), defName))
    if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
        def path = chooser.getSelectedFile().getAbsolutePath()
        if (!path.toLowerCase().endsWith(".tif") && !path.toLowerCase().endsWith(".tiff")) path += ".tif"
        IJ.saveAsTiff(labels, path)
        status.setText("Saved: ${path}")
    }
} as ActionListener)
