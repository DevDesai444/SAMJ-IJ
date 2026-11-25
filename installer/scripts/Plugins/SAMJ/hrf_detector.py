#!/usr/bin/env python3
"""HRF detector used by the SAMJ HRF AutoPrompter.

Runs the trained U-Net (smp, ResNet-34) over one OCT B-scan and prints the
bounding boxes of the hyperreflective foci it finds, as JSON, for SAMJ to use as
box prompts. It is meant to run inside SAMJ's own Python environment (the Appose
env that already has PyTorch), exactly like the SAM model does.

Usage:
    python hrf_detector.py <image.png> <weights.pt> <out.json> [threshold] [min_area]

It writes a JSON list of boxes: [{"x":.., "y":.., "w":.., "h":.., "cx":.., "cy":..}].
smp is installed on first use if it is missing; nothing else is downloaded.
"""
import sys, json, subprocess

THRESHOLD = 0.3      # lower favours recall (finding foci), which is what we want
MIN_AREA = 4         # drop single-pixel specks


def ensure_smp():
    try:
        import segmentation_models_pytorch  # noqa: F401
    except ImportError:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "-q",
                               "segmentation-models-pytorch"])


def normalise(img):
    import numpy as np
    img = img.astype("float32")
    lo, hi = np.percentile(img, 1), np.percentile(img, 99.5)
    img = np.clip((img - lo) / max(hi - lo, 1e-6), 0, 1)
    mean = np.array([0.485, 0.456, 0.406], "float32")
    std = np.array([0.229, 0.224, 0.225], "float32")
    x = np.stack([img, img, img], 0)
    return (x - mean[:, None, None]) / std[:, None, None]


def main():
    image_path, weights_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    threshold = float(sys.argv[4]) if len(sys.argv) > 4 else THRESHOLD
    min_area = int(sys.argv[5]) if len(sys.argv) > 5 else MIN_AREA

    ensure_smp()
    import numpy as np, torch
    import segmentation_models_pytorch as smp
    from PIL import Image
    from scipy import ndimage

    img = np.array(Image.open(image_path))
    if img.ndim == 3:            # a colour slice: use the first channel
        img = img[..., 0]
    height, width = img.shape
    padded_h = ((height + 31) // 32) * 32
    padded_w = ((width + 31) // 32) * 32
    padded = np.pad(img, ((0, padded_h - height), (0, padded_w - width)), mode="reflect")

    model = smp.Unet("resnet34", encoder_weights=None, in_channels=3, classes=1)
    model.load_state_dict(torch.load(weights_path, map_location="cpu"))
    model.eval()

    x = torch.from_numpy(normalise(padded)[None]).float()
    with torch.no_grad():
        prob = torch.sigmoid(model(x))[0, 0].numpy()[:height, :width]

    mask = (prob >= threshold).astype("uint8")
    labels, count = ndimage.label(mask)
    boxes = []
    for i in range(1, count + 1):
        ys, xs = np.where(labels == i)
        if len(xs) < min_area:
            continue
        x0, x1 = int(xs.min()), int(xs.max())
        y0, y1 = int(ys.min()), int(ys.max())
        boxes.append({"x": x0, "y": y0, "w": x1 - x0 + 1, "h": y1 - y0 + 1,
                      "cx": int(round(xs.mean())), "cy": int(round(ys.mean()))})

    json.dump(boxes, open(out_path, "w"))
    print("HRF_DETECTOR_FOUND %d" % len(boxes))


if __name__ == "__main__":
    main()
