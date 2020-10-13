package com.example.aicamera;

import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Rational;
import android.util.Size;

import androidx.lifecycle.ViewModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MDSpecs extends ViewModel {

    // CONSTANTS
    private static final int AUTO = 0;
    private static final int AUTO_RED_EYE = 1;
    private static final int SINGLE = 2;
    private static final int OFF = 3;
    private static final int TORCH = 4;

    // VARIABLES
    private CameraCharacteristics mCameraCharacteristics;
    private String mCameraId;
    private int mFlashMode;
    private boolean mIsFlashAvailable;
    private CameraManager mManager;
    private int mRatio = 0;
    private Rational[] mRatios = new Rational[4];
    private boolean[] mSupportedFlashModes = new boolean[2];

    // CONSTRUCTOR
    MDSpecs(Activity activity) throws CameraAccessException {
        mManager = (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
        initialSetup(activity);
        mCameraCharacteristics = mManager.getCameraCharacteristics(getCameraId());
    }

    // METHODS
    private Rational calculateRatio(double ratio) {
        int den = 1;
        int num;
        double temp;
        while (true) {
            temp = den * ratio;
            if (((int) temp) == temp) {
                num = (int) temp;
                break;
            }
            den++;
        }
        return new Rational(num, den);
    }

    Integer changeFlashMode() {
        switch(mFlashMode) {
            case AUTO: {
                if (mSupportedFlashModes[1]) {
                    mFlashMode = AUTO_RED_EYE;
                } else {
                    mFlashMode = SINGLE;
                }
                break;
            }
            case AUTO_RED_EYE: {
                mFlashMode = SINGLE;
                break;
            }
            case SINGLE: {
                mFlashMode = OFF;
                break;
            }
            case OFF: {
                mFlashMode = TORCH;
                break;
            }
            case TORCH: {
                if (mSupportedFlashModes[0]) {
                    mFlashMode = AUTO;
                } else if (mSupportedFlashModes[1]) {
                    mFlashMode = AUTO_RED_EYE;
                } else {
                    mFlashMode = SINGLE;
                }
                break;
            }
        }
        return mFlashMode;
    }

    void changeRatio() {
        int length = mRatios.length;
        int newRatio = mRatio + 1;
        if (newRatio < length && mRatios[newRatio] != null) {
            mRatio = newRatio;
        } else {
            mRatio = 0;
        }
    }

    Size chooseOptimalSize(Size[] choices, int maxWidth, int maxHeight, Size aspectRatio) {

        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();

        List<Size> matching = new ArrayList<>();
        for (Size option : choices) {
            int exp = option.getHeight() - option.getWidth() * h / w;
            if (Math.abs(exp) <= 5) {
                matching.add(option);
            }
        }
        List<Size> finalMatching = new ArrayList<>();
        for (Size option : matching) {
            if (option.getHeight() <= maxWidth && option.getWidth() <= maxHeight) {
                finalMatching.add(option);
            }
        }

        if (finalMatching.isEmpty()) {
            return Collections.min(matching, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
                }
            });
        } else {
            return Collections.max(finalMatching, new Comparator<Size>() {
                @Override
                public int compare(Size lhs, Size rhs) {
                    return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
                }
            });
        }
    }

    private static boolean contains(int[] modes, int mode) {
        if (modes == null) {
            return false;
        }
        for (int i : modes) {
            if (i == mode) {
                return true;
            }
        }
        return false;
    }

    String getCameraId() {
        return mCameraId;
    }

    Integer getFlashDrawable() {
        Integer id = null;
        switch (mFlashMode) {
            case AUTO: {
                id = R.drawable.ic_flash_auto_24px;
                break;
            }
            case AUTO_RED_EYE: {
                id = R.drawable.ic_remove_red_eye_24px;
                break;
            }
            case SINGLE: {
                id = R.drawable.ic_flash_on_24px;
                break;
            }
            case OFF: {
                id = R.drawable.ic_flash_off_24px;
                break;
            }
            case TORCH: {
                id = R.drawable.ic_wb_incandescent_24px;
                break;
            }
        }
        return id;
    }


    Integer getFlashMode() {
        return mFlashMode;
    }

    Size getLargestSize() {
        List<Size> matching = new ArrayList<>();
        StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        assert map != null;
        Size[] options = map.getOutputSizes(ImageFormat.JPEG);
        Rational preferredRatio = mRatios[mRatio];
        int width = preferredRatio.getNumerator();
        int height = preferredRatio.getDenominator();
        for (Size option: options) {
            if (Math.abs(option.getHeight() - option.getWidth() * height / width) < 5) {
                matching.add(option);
            }
        }
        return Collections.max(matching,
                new Comparator<Size>() {
                    @Override
                    public int compare(Size o1, Size o2) {
                        return Long.signum(o1.getHeight() * o1.getWidth() - o2.getHeight() * o2.getWidth());
                    }
                });
    }

    CameraManager getManager() {
        return mManager;
    }

    Size[] getOutputSizes() {
        StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        assert map != null;
        return map.getOutputSizes(SurfaceTexture.class);
    }

    Rational getRatio() {
        return mRatios[mRatio];
    }

    private void initialSetup(Activity activity) {

        StreamConfigurationMap map = null;
        try {
            String[] cameraIds = mManager.getCameraIdList();
            for (String cameraId : cameraIds) {
                CameraCharacteristics chars = mManager.getCameraCharacteristics(cameraId);
                Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    mCameraId = cameraId;
                    mIsFlashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        boolean[] supportedRatios = new boolean[4];
        supportedRatios[0] = true;
        supportedRatios[1] = false;
        supportedRatios[2] = false;
        supportedRatios[3] = false;

        assert map != null;
        Size[] options = map.getOutputSizes(ImageFormat.JPEG);
        for (Size option: options) {
            double ratioD = 1.0 * option.getWidth() / option.getHeight();
            Rational ratio = calculateRatio(ratioD);
            if (ratio.getNumerator() == 16 && ratio.getDenominator() == 9) {
                supportedRatios[1] = true;
            }
            if (ratio.getNumerator() == 4 && ratio.getDenominator() == 3) {
                supportedRatios[2] = true;
            }
            if (ratio.getNumerator() == 1 && ratio.getDenominator() == 1) {
                supportedRatios[3] = true;
            }
        }

        Point fullDisplaySize = new Point();
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
//            // there is another way
//        } else {
//            activity.getWindowManager().getDefaultDisplay().getRealSize(fullDisplaySize);
//        }

        activity.getWindowManager().getDefaultDisplay().getRealSize(fullDisplaySize);

        double screenRatioD = 1.0 * fullDisplaySize.y / fullDisplaySize.x;
        Rational screenRatio = calculateRatio(screenRatioD);

        if (screenRatio.getNumerator() == 16 && screenRatio.getDenominator() == 9) {
            mRatios[0] = new Rational(16, 9);
            if (supportedRatios[2] && supportedRatios[3]) {
                mRatios[1] = new Rational(4, 3);
                mRatios[2] = new Rational(1, 1);
            } else if (supportedRatios[2]) {
                mRatios[1] = new Rational(4, 3);
            } else {
                mRatios[1] = new Rational(1, 1);
            }
        } else if (screenRatio.getNumerator() == 4 && screenRatio.getDenominator() == 3) {
            mRatios[0] = new Rational(4, 3);
            if (supportedRatios[3]) {
                mRatios[1] = new Rational(1, 1);
            }
        } else if (screenRatio.getNumerator() == 1 && screenRatio.getDenominator() == 1) {
            mRatios[0] = new Rational(1, 1);
        } else {
            mRatios[0] = new Rational(screenRatio.getNumerator(), screenRatio.getDenominator());
            if (supportedRatios[1] && supportedRatios[2] && supportedRatios[3]) {
                mRatios[1] = new Rational(16, 9);
                mRatios[2] = new Rational(4, 3);
                mRatios[3] = new Rational(1, 1);
            } else if (supportedRatios[1] && supportedRatios[2]) {
                mRatios[1] = new Rational(16, 9);
                mRatios[2] = new Rational(4, 3);
            } else if (supportedRatios[2] && supportedRatios[3]) {
                mRatios[1] = new Rational(4, 3);
                mRatios[2] = new Rational(1, 1);
            } else if (supportedRatios[1] && supportedRatios[3]) {
                mRatios[1] = new Rational(16, 9);
                mRatios[2] = new Rational(1, 1);
            } else if (supportedRatios[1]) {
                mRatios[1] = new Rational(16, 9);
            } else if (supportedRatios[2]) {
                mRatios[1] = new Rational(4, 3);
            } else if (supportedRatios[3]) {
                mRatios[1] = new Rational(1, 1);
            }
        }
        try {
            setupSupportedFlashModes();
        } catch (CameraAccessException ignored) {

        }
    }

    boolean isFlashAvailable() {
        return mIsFlashAvailable;
    }

    void resetFlashMode() {
        if (mSupportedFlashModes[0]) {
            mFlashMode = AUTO;
        } else if (mSupportedFlashModes[1]) {
            mFlashMode = AUTO_RED_EYE;
        } else {
            mFlashMode = SINGLE;
        }
    }

    private void setupSupportedFlashModes() throws CameraAccessException {
        CameraCharacteristics chars = mManager.getCameraCharacteristics(mCameraId);
        mSupportedFlashModes[0] = contains(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        mSupportedFlashModes[1] = contains(chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
        if (mSupportedFlashModes[0]) {
            mFlashMode = AUTO;
        } else if (mSupportedFlashModes[1]) {
            mFlashMode = AUTO_RED_EYE;
        } else {
            mFlashMode = SINGLE;
        }
    }

}
