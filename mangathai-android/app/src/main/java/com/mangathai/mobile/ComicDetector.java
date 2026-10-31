package com.mangathai.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;

/** Runs the comic-text-detector ONNX model on the phone to find text blocks. */
public final class ComicDetector implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;

    public ComicDetector(File model) throws Exception {
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(4);
        session = environment.createSession(model.getAbsolutePath(), options);
    }

    /** @return boxes {left, top, right, bottom, confidence} in the bitmap's own pixels */
    public List<float[]> detect(Bitmap bitmap) throws Exception {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        DetectorMath.Letterbox lb = new DetectorMath.Letterbox(width, height);
        int size = DetectorMath.SIZE;

        Bitmap canvasBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, lb.newWidth, lb.newHeight, true);
        int[] pixels = new int[size * size];
        try {
            Canvas canvas = new Canvas(canvasBitmap);
            canvas.drawColor(Color.rgb(114, 114, 114));
            canvas.drawBitmap(scaled, lb.padLeft, lb.padTop, new Paint(Paint.FILTER_BITMAP_FLAG));
            canvasBitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        } finally {
            if (scaled != bitmap) scaled.recycle();
            canvasBitmap.recycle();
        }
        FloatBuffer input = FloatBuffer.allocate(3 * size * size);
        int plane = size * size;
        for (int i = 0; i < plane; i++) {
            int c = pixels[i];
            input.put(i, ((c >> 16) & 255) / 255f);
            input.put(plane + i, ((c >> 8) & 255) / 255f);
            input.put(2 * plane + i, (c & 255) / 255f);
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, input, new long[]{1, 3, size, size});
             OrtSession.Result result = session.run(Collections.singletonMap(session.getInputNames().iterator().next(), tensor))) {
            // Output 0 is "blk": [1, candidates, 5 + classes]
            float[][][] blk = (float[][][]) result.get(0).getValue();
            return DetectorMath.decode(blk[0], width, height);
        }
    }

    @Override
    public void close() {
        try { session.close(); } catch (Exception ignored) { }
    }
}
