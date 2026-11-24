#@ ImagePlus imp
/*
 * SAMJ 3D Linker (Groovy script)
 *
 * Give depth coherence to a stack of per-slice masks. SAMJ (and the SAMJ 3D
 * Annotator / HRF AutoPrompter) segment each B-scan on its own, so one focus
 * that spans several slices ends up as a pile of independent 2D masks. This
 * script links them: it treats every non-zero voxel as foreground and labels
 * the connected 3D components, so a focus running through many slices becomes a
 * single object with one ID.
 *
 * How to use:
 *   1. Open a label or mask stack (e.g. a "..._HRF_Labels" or "..._SAMJ_3D_Labels"
 *      stack produced by the other SAMJ scripts). Any stack works: non-zero means
 *      foreground.
 *   2. Run this script: Plugins > SAMJ > SAMJ 3D Linker.
 *   3. Pick the connectivity and, if you like, a minimum object size, then click
 *      "Link into 3D objects".
 *   4. A new 16-bit stack "..._Objects" is created where each connected 3D object
 *      has its own ID (1, 2, 3, ...). Save it as TIFF when happy.
 *
 * Connectivity: "26 (tolerant)" links voxels that touch even diagonally across
 * slices, which copes with a focus that drifts slightly from one B-scan to the
 * next; "6 (strict)" links only face-touching voxels. This is a plain
 * connected-components linker and works best on sparse masks such as HRF.
 */

import ij.IJ
import ij.ImagePlus
import ij.ImageStack
import ij.process.ShortProcessor

import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.ActionListener

// ------------------------------------------------------------------
// 1. sanity: we need a stack of masks
// ------------------------------------------------------------------
if (imp == null) {
    JOptionPane.showMessageDialog(null,
        "Open a label or mask stack first, then run this script again.",
        "SAMJ 3D Linker", JOptionPane.ERROR_MESSAGE)
    return
}
if (imp.getStackSize() < 2) {
    JOptionPane.showMessageDialog(null,
        "Active image is single-slice. The linker joins masks ACROSS slices,\n" +
        "so open a stack of per-slice masks first.",
        "SAMJ 3D Linker", JOptionPane.WARNING_MESSAGE)
    return
}

def W = imp.getWidth()
def H = imp.getHeight()
def D = imp.getStackSize()
def sourceTitle = imp.getTitle()

// ------------------------------------------------------------------
// 2. helpers
// ------------------------------------------------------------------

// A voxel's linear key. z, y, x are 0-based.
def keyOf = { int x, int y, int z -> (z * H + y) * W + x }

// The neighbour offsets for the chosen connectivity (only the ones we union with).
def neighbourOffsets = { boolean diagonal ->
    def offsets = new ArrayList<int[]>()
    for (int dz = -1; dz <= 1; dz++)
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                int steps = Math.abs(dx) + Math.abs(dy) + Math.abs(dz)
                if (!diagonal && steps != 1) continue   // 6-connectivity keeps face neighbours only
                offsets.add([dx, dy, dz] as int[])
            }
    return offsets
}

// Union-find over voxel keys, stored sparsely so only foreground voxels cost memory.
def parent = new HashMap<Integer, Integer>()
def find = { int a ->
    int r = a
    while (parent.get(r) != r) {
        parent.put(r, parent.get(parent.get(r)))   // path halving keeps the tree flat
        r = parent.get(r)
    }
    return r
}
def union = { int a, int b ->
    parent.putIfAbsent(a, a)
    parent.putIfAbsent(b, b)
    int ra = find(a)
    int rb = find(b)
    if (ra != rb) parent.put(ra, rb)
}

// ------------------------------------------------------------------
// 3. the linking pass
// ------------------------------------------------------------------
def linkObjects = { boolean diagonal, int minSize ->
    parent.clear()
    def stack = imp.getStack()
    // Hold a processor reference per slice; these point at the stack's own pixels.
    def procs = (1..D).collect { stack.getProcessor(it) }
    def offsets = neighbourOffsets(diagonal)

    // Union every foreground voxel with its foreground neighbours.
    for (int z = 0; z < D; z++) {
        def proc = procs[z]
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (proc.get(x, y) == 0) continue
                int k = keyOf(x, y, z)
                parent.putIfAbsent(k, k)
                for (int[] o : offsets) {
                    int nx = x + o[0]
                    int ny = y + o[1]
                    int nz = z + o[2]
                    if (nx < 0 || nx >= W || ny < 0 || ny >= H || nz < 0 || nz >= D) continue
                    if (procs[nz].get(nx, ny) != 0) union(k, keyOf(nx, ny, nz))
                }
            }
        }
    }

    // Count the voxels under each root so small specks can be dropped.
    def sizeOfRoot = new HashMap<Integer, Integer>()
    parent.keySet().each { k ->
        int r = find(k)
        sizeOfRoot.put(r, (sizeOfRoot.get(r) ?: 0) + 1)
    }

    // Give each surviving root a sequential object ID.
    def idOfRoot = new HashMap<Integer, Integer>()
    int nextId = 1
    sizeOfRoot.each { root, size ->
        if (size >= minSize) {
            idOfRoot.put(root, nextId)
            nextId++
        }
    }
    int objectCount = nextId - 1
    if (objectCount > 65535)
        IJ.log("[SAMJ Linker] WARNING: ${objectCount} objects exceed the 16-bit range; IDs above 65535 will wrap.")

    // Write the object IDs into a fresh 16-bit stack.
    def outStack = new ImageStack(W, H)
    for (int z = 0; z < D; z++) outStack.addSlice(new ShortProcessor(W, H))
    def procsOut = (1..D).collect { outStack.getProcessor(it) }
    parent.keySet().each { k ->
        int id = idOfRoot.get(find(k)) ?: 0
        if (id != 0) {
            int z = (int) (k / (W * H))
            int rem = k % (W * H)
            int y = (int) (rem / W)
            int x = rem % W
            procsOut[z].set(x, y, id)
        }
    }

    def outTitle = sourceTitle.replaceFirst(/\.[^.]+$/, "") + "_Objects"
    def outImp = new ImagePlus(outTitle, outStack)
    outImp.show()
    return [outImp, objectCount]
}

// ------------------------------------------------------------------
// 4. UI
// ------------------------------------------------------------------
def frame = new JFrame("SAMJ 3D Linker — " + sourceTitle)
frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE)

def panel = new JPanel()
panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS))
panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10))

// Row: options
def optionRow = new JPanel(new FlowLayout(FlowLayout.LEFT))
optionRow.add(new JLabel("Connectivity:"))
def connCombo = new JComboBox<>(["26 (tolerant)", "6 (strict)"] as String[])
optionRow.add(connCombo)
optionRow.add(new JLabel("Min object size (voxels):"))
def minSizeSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 1000000, 1))
minSizeSpinner.setPreferredSize(new Dimension(90, 24))
optionRow.add(minSizeSpinner)
panel.add(optionRow)

// Row: run
def runBtn = new JButton("Link into 3D objects")
runBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
panel.add(runBtn)

// Row: save
def saveBtn = new JButton("Save objects as TIFF…")
saveBtn.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT)
saveBtn.setEnabled(false)
panel.add(saveBtn)

// Row: status
def status = new JLabel("Choose options and press Link into 3D objects.")
status.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0))
panel.add(status)

frame.add(panel, BorderLayout.CENTER)
frame.pack()
frame.setLocationRelativeTo(null)
frame.setVisible(true)

// the most recent result, so Save knows what to write
def result = [ objects: null ]

// ------------------------------------------------------------------
// 5. handlers
// ------------------------------------------------------------------
runBtn.addActionListener({ e ->
    boolean diagonal = (connCombo.getSelectedItem() as String).startsWith("26")
    int minSize = (minSizeSpinner.getValue() as Integer).intValue()

    runBtn.setEnabled(false)
    saveBtn.setEnabled(false)
    status.setText("Linking … (one pass over ${W}x${H}x${D})")

    Thread.start {
        try {
            def linked = linkObjects(diagonal, minSize)
            result.objects = linked[0]
            int count = linked[1]
            SwingUtilities.invokeLater {
                status.setText("Linked into ${count} 3D object(s). See the ${result.objects.getTitle()} window.")
                runBtn.setEnabled(true)
                saveBtn.setEnabled(true)
            }
        } catch (Throwable ex) {
            ex.printStackTrace()
            SwingUtilities.invokeLater {
                status.setText("Linking failed: ${ex.getClass().getSimpleName()}: ${ex.getMessage()}")
                runBtn.setEnabled(true)
            }
        }
    }
} as ActionListener)

saveBtn.addActionListener({ e ->
    if (result.objects == null) return
    def chooser = new JFileChooser()
    def defName = sourceTitle.replaceFirst(/\.[^.]+$/, "") + "_Objects.tif"
    chooser.setSelectedFile(new File(System.getProperty("user.home"), defName))
    if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
        def path = chooser.getSelectedFile().getAbsolutePath()
        if (!path.toLowerCase().endsWith(".tif") && !path.toLowerCase().endsWith(".tiff")) path += ".tif"
        IJ.saveAsTiff(result.objects, path)
        status.setText("Saved: ${path}")
    }
} as ActionListener)
