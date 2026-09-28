#@ ImagePlus imp
/*
 * SAMJ 3D Annotator (Groovy script)
 *
 * Annotate a 3D stack slice by slice without ever leaving the volume window:
 *   1. Open your 3D stack in Fiji (grayscale or RGB per slice).
 *   2. Run this script: Plugins > SAMJ > SAMJ 3D Annotator.
 *   3. Pick a SAM model in the panel, click Load model (only slow the first time).
 *   4. Navigate to any slice on your 3D window and draw a rectangle ROI.
 *   5. Click "Segment current slice" -- the resulting binary mask is painted
 *      into a companion 3D label stack ("<title>_SAMJ_3D_Labels") at that Z,
 *      using the label ID from the spinner. Advance the slice slider, repeat.
 *   6. Click "Save 3D labels" when finished (or File > Save As on the labels window).
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
import ij.process.ShortProcessor

// Belt-and-suspenders: force the SAM env root to Fiji/appose_arm64 (or the
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
import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.display.imagej.ImageJFunctions

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
                status.setText("${name} ready. On your ${sourceTitle} window: pick a slice, draw a rectangle, press Segment.")
                segBtn.setEnabled(true)
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
    def activeImp = imp  // the source stack; SAMJ works from THIS window, not any duplicate
    def roi = activeImp.getRoi()
    if (roi == null) {
        status.setText("Draw a rectangle ROI on the ${sourceTitle} window first.")
        return
    }
    def bounds = roi.getBounds()
    def rectList = new ArrayList<java.awt.Rectangle>()
    rectList.add(new java.awt.Rectangle(bounds.x, bounds.y, bounds.width, bounds.height))
    def pointList = new ArrayList<int[]>()
    def sliceIdx = activeImp.getCurrentSlice()  // 1-based
    def labelValue = (labelSpinner.getValue() as Integer).intValue()

    // Extract slice N as a standalone 2D ImagePlus and wrap as an ImgLib2 RAI
    def sliceProc = activeImp.getStack().getProcessor(sliceIdx).duplicate()
    def sliceImp = new ImagePlus("slice_${sliceIdx}", sliceProc)
    RandomAccessibleInterval rai = ImageJFunctions.wrapReal(sliceImp)

    segBtn.setEnabled(false)
    status.setText("Segmenting slice ${sliceIdx} with rectangle ${bounds.width}x${bounds.height} …")

    Thread.start {
        try {
            def maskRai = SAMJ_Annotator.samJReturnMask(state.model, rai, pointList, rectList)

            // Paint mask (non-zero) into labels stack at slice sliceIdx with labelValue
            def dstProc = labels.getStack().getProcessor(sliceIdx) as ShortProcessor
            def maskAccess = maskRai.randomAccess()
            def pos = new long[2]
            int painted = 0
            for (int y = 0; y < H; y++) {
                pos[1] = y
                for (int x = 0; x < W; x++) {
                    pos[0] = x
                    maskAccess.setPosition(pos)
                    int v = maskAccess.get().getInteger()
                    if (v != 0) {
                        dstProc.set(x, y, labelValue)
                        painted++
                    }
                }
            }
            labels.setSlice(sliceIdx)
            labels.updateAndDraw()

            SwingUtilities.invokeLater {
                if (autoIncr.isSelected()) labelSpinner.setValue(labelValue + 1)
                status.setText("Slice ${sliceIdx}: painted ${painted} px with label ${labelValue}. Advance a slice and draw another ROI.")
                segBtn.setEnabled(true)
            }
        } catch (Throwable ex) {
            ex.printStackTrace()
            SwingUtilities.invokeLater {
                status.setText("Segment failed on slice ${sliceIdx}: ${ex.getClass().getSimpleName()}: ${ex.getMessage()}")
                segBtn.setEnabled(true)
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
