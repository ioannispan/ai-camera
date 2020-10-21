package com.example.aicamera;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.os.Build;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

class Classifier {

    // VARIABLES
    private final double mAccuracyValue;
    private final int mBatchSize;
    private ByteBuffer mImageData;
    private final float mImageMean;
    private final int mImageSizeX;
    private final int mImageSizeY;
    private final float mImageStd;
    private final boolean mIsModelQuantized;
    private final List<String> mLabelList;
    private final String mLabelPath;
    private final String mModelPath;
    private final int mPixelSize;
    private Interpreter mTfLite;

    // CONSTRUCTOR
    public Classifier(Activity activity) throws IOException {

        // model parameters
        mModelPath = "efficientnet_lite0_224.tflite";
        mLabelPath = "imagenet_labels_1000.txt";
        mIsModelQuantized = false;
        mImageSizeX = 224;
        mImageSizeY = 224;
        mPixelSize = 3;
        mBatchSize = 1;
        mImageMean = 127.5f;
        mImageStd = 127.5f;

        // hardware parameters
        boolean useGPU = false;
        boolean useNNAPI = false;
        int numThreads = 2;

        // other parameters
        mAccuracyValue = 0.1;

        Interpreter.Options options = new Interpreter.Options();
        if (useGPU) {
            options.addDelegate(new GpuDelegate());
        }
        if (useNNAPI) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
                options.setUseNNAPI(true);
            }
        }
        if (numThreads > 0) {
            options.setNumThreads(numThreads);
        }
        mTfLite = new Interpreter(loadModelFile(activity), options);
        mLabelList = loadLabelList(activity);
    }

    // METHODS
    String classify(Bitmap bitmap) {
        if (mTfLite == null) {
            return "";
        }
        String textToShow;
        if (mIsModelQuantized) {
            convertBitmapToByteBuffer(bitmap);
            byte[][] labelProbArray = new byte[mBatchSize][mLabelList.size()];
            mTfLite.run(mImageData, labelProbArray);
            textToShow = getTopResultByte(labelProbArray);
        } else {
            convertBitmapToByteBuffer(bitmap);
            float[][] labelProbArray = new float[mBatchSize][mLabelList.size()];
            mTfLite.run(mImageData, labelProbArray);
            textToShow = getTopResultFloat(labelProbArray);
        }
        return textToShow;
    }

    void close() {
        mTfLite.close();
        mTfLite = null;
    }

    private void convertBitmapToByteBuffer(Bitmap bitmap) {

        if (mIsModelQuantized) {
            mImageData = ByteBuffer.allocateDirect(mBatchSize * mImageSizeX * mImageSizeY * mPixelSize);
        } else {
            mImageData = ByteBuffer.allocateDirect(4 * mBatchSize * mImageSizeX * mImageSizeY * mPixelSize);
        }
        mImageData.order(ByteOrder.nativeOrder());

        int[] intValues = new int[mImageSizeX * mImageSizeY];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        int pixel = 0;
        for (int i = 0; i < mImageSizeX; ++i) {
            for (int j = 0; j < mImageSizeY; ++j) {
                final int val = intValues[pixel++];
                if (mIsModelQuantized) {
                    mImageData.put((byte) ((val >> 16) & 0xFF));
                    mImageData.put((byte) ((val >> 8) & 0xFF));
                    mImageData.put((byte) (val & 0xFF));
                } else {
                    mImageData.putFloat((((val >> 16) & 0xFF)-mImageMean)/mImageStd);
                    mImageData.putFloat((((val >> 8) & 0xFF)-mImageMean)/mImageStd);
                    mImageData.putFloat((((val) & 0xFF)-mImageMean)/mImageStd);
                }
            }
        }
    }

    int getImageSizeX() {
        return mImageSizeX;
    }

    int getImageSizeY() {
        return mImageSizeY;
    }

    private String getTopResultByte(byte[][] labelProbArray) {
        byte[] array = labelProbArray[0];
        List<Byte> list = new ArrayList<>(array.length);
        for (byte b : array) {
            list.add(b);
        }
        byte value = Collections.max(list);
        float valueFloat = (value & 0xff) / 255.0f;
        if (valueFloat > mAccuracyValue) {
            int index = list.indexOf(value);
            return mLabelList.get(index);
        } else {
            return "";
        }
    }

    private String getTopResultFloat(float[][] labelProbArray) {
        float[] array = labelProbArray[0];
        List<Float> list = new ArrayList<>(array.length);
        for (float f : array) {
            list.add(f);
        }
        float value = Collections.max(list);
        if (value > mAccuracyValue) {
            int index = list.indexOf(value);
            return mLabelList.get(index);
        } else {
            return "";
        }
    }

    private List<String> loadLabelList(Activity activity) throws IOException {
        List<String> labelList = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(activity.getAssets().open(mLabelPath)));
        String line;
        while ((line = reader.readLine()) != null) {
            labelList.add(line);
        }
        reader.close();
        return labelList;
    }

    private ByteBuffer loadModelFile(Activity activity) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd(mModelPath);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        MappedByteBuffer mbb = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
        fileChannel.close();
        inputStream.close();
        fileDescriptor.close();
        return mbb;
    }

}
