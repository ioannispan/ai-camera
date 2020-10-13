package com.example.aicamera;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

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
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;

class Classifier {

    // CONSTANTS
    private static final String TAG = "ImageClassifier";

    // VARIABLES
    private double mAccuracyValue;
    private int mBatchSize;
    private ByteBuffer mImageData;
    private float mImageMean;
    private int mImageSizeX;
    private int mImageSizeY;
    private float mImageStd;
    private boolean mIsModelQuantized;
    private List<String> mLabelList;
    private String mLabelPath;
    private String mModelPath;
    private int mPixelSize;
    private int mResultsToShow;
    private PriorityQueue<Map.Entry<String, Float>> mSortedLabels;
    private Interpreter mTfLite;

    // CONSTRUCTOR
    public Classifier(Activity activity) throws IOException {

        // model parameters
        mModelPath = "efficientnet_lite4_300.tflite";
        mLabelPath = "imagenet_labels_1000.txt";
        mIsModelQuantized = false;
        mImageSizeX = 300;
        mImageSizeY = 300;
        mPixelSize = 3;
        mBatchSize = 1;
        mImageMean = 127.5f;
        mImageStd = 127.5f;

        // hardware parameters
        boolean useGPU = false;
        boolean useNNAPI = false;
        int numThreads = 2;

        // other parameters
        mAccuracyValue = 0.2;
        mResultsToShow = 1;
        mSortedLabels = new PriorityQueue<>(
                mResultsToShow,
                new Comparator<Map.Entry<String, Float>>() {
                    @Override
                    public int compare(Map.Entry<String, Float> o1, Map.Entry<String, Float> o2) {
                        return (o1.getValue()).compareTo(o2.getValue());
                    }
                });

        Interpreter.Options options = new Interpreter.Options();
        if (useGPU) {
            options.addDelegate(new GpuDelegate());
        }
        if (useNNAPI) {
            options.setUseNNAPI(true);
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
            //Log.e(TAG, "Image classifier has not been initialized; Skipped.");
            return "Uninitialized Classifier.";
        }

        String textToShow;

        if (mIsModelQuantized) {
            convertBitmapToByteBuffer(bitmap);
            byte[][] labelProbArray = new byte[mBatchSize][mLabelList.size()];
            mTfLite.run(mImageData, labelProbArray);
            if (mResultsToShow > 1) {
                textToShow = getSortedResultsByte(labelProbArray);
            } else {
                textToShow = getTopResultByte(labelProbArray);
            }
        } else {
            convertBitmapToByteBuffer(bitmap);
            float[][] labelProbArray = new float[mBatchSize][mLabelList.size()];
            mTfLite.run(mImageData, labelProbArray);
            if (mResultsToShow > 1) {
                textToShow = getSortedResultsFloat(labelProbArray);
            } else {
                textToShow = getTopResultFloat(labelProbArray);
            }
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

    int getNumResults() {
        return mResultsToShow;
    }

    private String getSortedResultsByte(byte[][] labelProbArray) {
        for (int i = 0; i < mLabelList.size(); ++i) {
            mSortedLabels.add(new AbstractMap.SimpleEntry<>(mLabelList.get(i), (labelProbArray[0][i] & 0xff) / 255.0f));
            if (mSortedLabels.size() > mResultsToShow) {
                mSortedLabels.poll();
            }
        }
        StringBuilder textToShow = new StringBuilder();
        for (int i = 0; i < mResultsToShow; ++i) {
            Map.Entry<String, Float> label = mSortedLabels.poll();
            assert label != null;
            if (label.getValue() > mAccuracyValue) {
                if (i == mResultsToShow - 1) {
                    textToShow.insert(0, String.format(Locale.getDefault(), "%s: %4.2f", label.getKey(), label.getValue()));
                } else {
                    textToShow.insert(0, String.format(Locale.getDefault(), "\n%s: %4.2f", label.getKey(), label.getValue()));
                }
            }
        }
        return textToShow.toString();
    }

    private String getSortedResultsFloat(float[][] labelProbArray) {
        for (int i = 0; i < mLabelList.size(); ++i) {
            mSortedLabels.add(new AbstractMap.SimpleEntry<>(mLabelList.get(i), labelProbArray[0][i]));
            if (mSortedLabels.size() > mResultsToShow) {
                mSortedLabels.poll();
            }
        }
        StringBuilder textToShow = new StringBuilder();
        for (int i = 0; i < mResultsToShow; ++i) {
            Map.Entry<String, Float> label = mSortedLabels.poll();
            assert label != null;
            if (label.getValue() > mAccuracyValue) {
                if (i == mResultsToShow - 1) {
                    textToShow.insert(0, String.format(Locale.getDefault(), "%s: %4.2f", label.getKey(), label.getValue()));
                } else {
                    textToShow.insert(0, String.format(Locale.getDefault(), "\n%s: %4.2f", label.getKey(), label.getValue()));
                }
            }
        }
        return textToShow.toString();
    }

    private String getTopResultByte(byte[][] labelProbArray) {
        byte[] array = labelProbArray[0];
        List<Byte> list = new ArrayList<>(array.length);
        for (byte b : array) {
            list.add(b);
        }
        byte value = (byte) Collections.max(list);
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
        float value = (float) Collections.max(list);
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
