package com.example.aicamera;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Rational;
import android.util.Size;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraActivity extends AppCompatActivity {

    // CONSTANTS
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final int MAX_PREVIEW_WIDTH = 1080;
    private static final int MAX_PREVIEW_HEIGHT = 1920;
    private static final int PRECAPTURE_TIMEOUT_MS = 1000;

        // camera state
    private static final int STATE_CLOSED = -1;
    private static final int STATE_OPENED = 0;
    private static final int STATE_PREVIEW = 1;
    private static final int STATE_WAITING_FOR_3A_CONVERGENCE = 2;

        // flash modes
    private static final int AUTO = 0;
    private static final int AUTO_RED_EYE = 1;
    private static final int SINGLE = 2;
    private static final int OFF = 3;
    private static final int TORCH = 4;

    // VARIABLES
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;
    private CameraCaptureSession mCameraCaptureSession;
    private CameraDevice mCameraDevice;
    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {

            mCameraOpenCloseLock.release();
            mState = STATE_OPENED;
            mCameraDevice = camera;

            // Start the preview session if the TextureView has been already set up.
            if (mPreviewSize != null && mTextureView.isAvailable()) {
                try {
                    startCameraPreview();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mState = STATE_CLOSED;
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mState = STATE_CLOSED;
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }
    };
    private final Semaphore mCameraOpenCloseLock = new Semaphore(1);
    private long mCaptureTimer;
    private Classifier mClassifier;
    private Size mFinalSize;
    private float mFingerSpacing = 0;
    private ImageButton mFlashButton;
    private String mImageFileLocation;
    private ImageReader mImageReader;
    private String mLabel = "";
    private Float mMaximumZoomLevel;
    private MDSpecs mMobileDevice;
    private boolean mNoAFRun = false;
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(final ImageReader reader) {
            if (reader == null) {
                return;
            }
            final Image image = reader.acquireNextImage();
            mBackgroundHandler.post(new Runnable() {
                @Override
                public void run() {
                    ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[byteBuffer.remaining()];
                    byteBuffer.get(bytes);
                    try {
                        mOutputStream.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (mOutputStream != null) {
                            try {
                                mOutputStream.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            });
        }
    };
    private final View.OnTouchListener mOnTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_MOVE: {
                    try {
                        Rect rect = mMobileDevice.getCameraCharacteristics().get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                        if (rect == null) {
                            return false;
                        }
                        float currentFingerSpacing;
                        if (event.getPointerCount() == 2) { // Multi touch.
                            currentFingerSpacing = getFingerSpacing(event);
                            float delta = 0.025f; // Control this value to control the zooming sensibility
                            if (mFingerSpacing != 0) {
                                if (currentFingerSpacing > mFingerSpacing) { // Don't over zoom-in
                                    if ((mMaximumZoomLevel - mZoomLevel) <= delta) {
                                        delta = mMaximumZoomLevel - mZoomLevel;
                                    }
                                    mZoomLevel = mZoomLevel + delta;
                                } else if (currentFingerSpacing < mFingerSpacing) { // Don't over zoom-out
                                    if ((mZoomLevel - delta) < 1f) {
                                        delta = mZoomLevel - 1f;
                                    }
                                    mZoomLevel = mZoomLevel - delta;
                                }

                                float tempZoomLevel = (float) ((mZoomLevel-1.4)*(mMaximumZoomLevel-1)/(mMaximumZoomLevel-1.4) + 1);
                                int progress = Math.round((tempZoomLevel-1)*100);

                                if (tempZoomLevel > 1.0) {
                                    mProgressBarTop.setVisibility(View.VISIBLE);
                                    mProgressBarBottom.setVisibility(View.VISIBLE);
                                    mTextViewZoom.setVisibility(View.VISIBLE);

                                    mProgressBarTop.setProgress(progress);
                                    mProgressBarBottom.setProgress(progress);
                                    mTextViewZoom.setText(String.format(Locale.getDefault(), "%.1f", tempZoomLevel));
                                } else if (tempZoomLevel <= 1.0) {
                                    mProgressBarTop.setVisibility(View.INVISIBLE);
                                    mProgressBarBottom.setVisibility(View.INVISIBLE);
                                }

                                // This ratio is the ratio of cropped Rect to Camera's original (Maximum) Rect
                                float ratio = (float) 1 / mZoomLevel;
                                // croppedWidth and croppedHeight are the pixels cropped away, not pixels after cropped
                                int croppedWidth = rect.width() - Math.round((float) rect.width() * ratio);
                                int croppedHeight = rect.height() - Math.round((float) rect.height() * ratio);
                                // Finally, mZoom represents the zoomed visible area
                                mZoom = new Rect(croppedWidth / 2, croppedHeight / 2,
                                        rect.width() - croppedWidth / 2, rect.height() - croppedHeight / 2);
                                mPreviewCaptureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, mZoom);
                            }
                            mFingerSpacing = currentFingerSpacing;
                        } else { // Single touch point, needs to return true in order to detect one more touch point
                            return true;
                        }
                        mCameraCaptureSession.setRepeatingRequest(mPreviewCaptureRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
                        return true;
                    } catch (final Exception e) {
                        // Error handling
                        return true;
                    }
                }
                case MotionEvent.ACTION_UP: {
                    v.performClick();
                    new CountDownTimer(1200, 1200) {
                        @Override
                        public void onTick(long millisUntilFinished) {

                        }

                        public void onFinish() {
                            mProgressBarTop.setVisibility(View.INVISIBLE);
                            mProgressBarBottom.setVisibility(View.INVISIBLE);
                            mTextViewZoom.setVisibility(View.INVISIBLE);
                        }
                    }.start();
                    break;
                }
                default: {
                    break;
                }
            }
            return true;
        }
    };
    private OutputStream mOutputStream;
    private final Runnable mPeriodicClassify = new Runnable() {
        @Override
        public void run() {
            new CountDownTimer(1000, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {

                }

                public void onFinish() {
                    if (mRunClassifier) {
                        classifyFrame();
                        if (mBackgroundHandler != null) {
                            mBackgroundHandler.post(mPeriodicClassify);
                        }
                    }
                }
            }.start();
        }
    };
    private CaptureRequest.Builder mPreviewCaptureRequestBuilder;
    private final CameraCaptureSession.StateCallback mPreviewSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            if (mCameraDevice == null) {
                return;
            }
            mCameraCaptureSession = session;
            setup3AControlsAndZoom(mPreviewCaptureRequestBuilder);
            try {
                mCameraCaptureSession.setRepeatingRequest(mPreviewCaptureRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
            mState = STATE_PREVIEW;
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {

        }
    };
    private Size mPreviewSize;
    private ProgressBar mProgressBarBottom, mProgressBarTop;
    private boolean mRunClassifier = false;
    private final CameraCaptureSession.CaptureCallback mSessionCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            try {
                process(result);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult) {
            try {
                process(partialResult);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
            super.onCaptureFailed(session, request, failure);
        }

        private void process(CaptureResult result) throws CameraAccessException {
            switch (mState) {
                case STATE_PREVIEW: {
                    break;
                }
                case STATE_WAITING_FOR_3A_CONVERGENCE: {
                    boolean readyToCapture = true;
                    if (!mNoAFRun) {
                        Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                        if (afState == null) {
                            break;
                        }

                        // If auto-focus has reached locked state, we are ready to capture
                        readyToCapture = (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED);
                    }
                    // If we are running on an non-legacy device, we should also wait until
                    // auto-exposure and auto-white-balance have converged as well before
                    // taking a picture.
                    if (isNotLegacyLocked()) {
                        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                        Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
                        if (aeState == null || awbState == null) {
                            break;
                        }

                        readyToCapture = readyToCapture &&
                                aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED &&
                                awbState == CaptureResult.CONTROL_AWB_STATE_CONVERGED;
                    }
                    // If we haven't finished the pre-capture sequence but have hit our maximum
                    // wait timeout, too bad! Begin capture anyway.
                    if (!readyToCapture && hitTimeoutLocked()) {
                        readyToCapture = true;
                    }
                    if (readyToCapture) {
                        // Capture once for each user tap of the "Picture" button.
                        captureStillImage();
                        // After this, the camera will go back to the normal state of preview.
                        mState = STATE_PREVIEW;
                    }
                }
            }
        }

    };
    private int mState = STATE_CLOSED;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            mPreviewSize = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

        }
    };
    private TextureView mTextureView;
    private TextView mTextView;
    private TextView mTextViewZoom;
    private Rect mZoom;
    private float mZoomLevel = 1f;

    // BASIC METHODS
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkPermissions();

        mTextureView = findViewById(R.id.textureView);
        mTextView = findViewById(R.id.labelTextView);
        mTextViewZoom = findViewById(R.id.textViewZoom);
        mProgressBarTop = findViewById(R.id.progressBarTop);
        mProgressBarBottom = findViewById(R.id.progressBarBottom);

        ImageButton mCaptureButton = findViewById(R.id.captureButton);
        mCaptureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prepareForCapture();
            }
        });

        ImageButton mRatioButton = findViewById(R.id.ratioButton);
        mRatioButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeRatio();
            }
        });

        mFlashButton = findViewById(R.id.flashButton);
        mFlashButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                changeFlash();
            }
        });

        final Activity activity = this;
        mMobileDevice = new ViewModelProvider(this, new ViewModelProvider.Factory() {
            @NonNull
            @Override
            public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
                MDSpecs model = new MDSpecs(activity);
                return (T) model;
            }
        }).get(MDSpecs.class);

        // Setup zoom
        mMaximumZoomLevel = mMobileDevice.getCameraCharacteristics().get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        assert mMaximumZoomLevel != null;
        mTextureView.setOnTouchListener(mOnTouchListener);
        mProgressBarTop.setProgress(0);
        mProgressBarTop.setMax(Math.round((mMaximumZoomLevel-1)*100));
        mProgressBarBottom.setProgress(0);
        mProgressBarBottom.setMax(Math.round((mMaximumZoomLevel-1)*100));
        ConstraintLayout constraintLayout = findViewById(R.id.constraintLayout);
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(constraintLayout);
        constraintSet.setMargin(R.id.constraintLayoutZoom, ConstraintSet.END, 24);

        // Setup flash
        if (!mMobileDevice.isFlashAvailable()) {
            mFlashButton.setVisibility(View.INVISIBLE);
        } else {
            int id = mMobileDevice.getFlashDrawable();
            mFlashButton.setImageResource(id);
        }

        // Setup ratio
        int id = mMobileDevice.getRatioDrawable();
        mRatioButton.setImageResource(id);

        constraintSet.applyTo(constraintLayout);
    }

    @Override
    protected void onResume() {
        super.onResume();

		View decorView = getWindow().getDecorView();
		decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
				| View.SYSTEM_UI_FLAG_FULLSCREEN
				| View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
		ActionBar actionBar = getActionBar();
		if (actionBar != null) {
			actionBar.hide();
		}

        startBackgroundThread();

        if (mTextureView.isAvailable() ) {
            openCamera();
        }
        else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        try {
            stopBackgroundThread();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (mClassifier != null) {
            mClassifier.close();
        }
        super.onDestroy();
    }

    protected void changeRatio() {
        mMobileDevice.changeRatio();
        int flashMode = mMobileDevice.getFlashMode();
        if (flashMode == TORCH) {
            mMobileDevice.resetFlashMode();
        }
        recreate();
    }

    protected void changeFlash() {
        int newFlashMode = mMobileDevice.changeFlashMode();
        switch(newFlashMode) {
            case AUTO: {
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
            }
            case AUTO_RED_EYE: {
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
                break;
            }
            case SINGLE: {
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                mPreviewCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);
                break;
            }
            case OFF: {
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                mPreviewCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                break;
            }
            case TORCH: {
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                mPreviewCaptureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                break;
            }
        }
        try {
            mCameraCaptureSession.setRepeatingRequest(mPreviewCaptureRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        int id = mMobileDevice.getFlashDrawable();
        mFlashButton.setImageResource(id);
    }

    // OTHER METHODS
    private void captureStillImage() throws CameraAccessException {

        if (mCameraDevice == null) {
            return;
        }

        CaptureRequest.Builder captureStillBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        captureStillBuilder.addTarget(mImageReader.getSurface());

        // Use the same AE and AF modes as the preview.
        setup3AControlsAndZoom(captureStillBuilder);
        captureStillBuilder.set(CaptureRequest.JPEG_ORIENTATION, 90);
        CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {

            @Override
            public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    String imageFileName = "JPEG_" + timeStamp + ".jpg";
                    File storageDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
                    File imageFile = new File(storageDir, imageFileName);
                    mImageFileLocation = imageFile.toString();
                    try {
                        mOutputStream = new FileOutputStream(imageFile);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                } else {
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    String imageFileName = "JPEG_" + timeStamp;
                    ContentResolver contentResolver = getContentResolver();
                    Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.TITLE, imageFileName);
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, imageFileName);
                    values.put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis());
                    values.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                    Uri item = contentResolver.insert(collection, values);
                    if (item != null) {
                        try {
                            mOutputStream = contentResolver.openOutputStream(item);
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        }
                        values.clear();
                    }
                }
            }

            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                finishedCaptureLocked();
                Toast.makeText(getApplicationContext(), "Image saved", Toast.LENGTH_SHORT).show();
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    // Tell the media scanner about the new file so that it is immediately available to the user.
                    MediaScannerConnection.scanFile(CameraActivity.this,
                            new String[]{mImageFileLocation}, null,null);
                }
            }

            @Override
            public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure) {
                finishedCaptureLocked();
            }
        };
        mCameraCaptureSession.capture(captureStillBuilder.build(), captureCallback, mBackgroundHandler);
    }

    private void checkPermissions() {
        ArrayList<String> perms = new ArrayList<>();
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.CAMERA);
        }
        if (perms.size() > 0) {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
        while (true) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                break;
            }
        }
    }

    private void classifyFrame() {
        if (mClassifier == null || mCameraDevice == null) {
            return;
        }
        Bitmap bitmap = prepareBitmap();
        String label = mClassifier.classify(bitmap);
        if (!label.isEmpty()) {
            mLabel = label.substring(0, 1).toUpperCase() + label.substring(1);
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mTextView.setText(mLabel);
            }
        });
    }

    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            mState = STATE_CLOSED;
            if (mCameraCaptureSession != null) {
                mCameraCaptureSession.close();
                mCameraCaptureSession = null;
            }
            if (mCameraDevice != null) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (mImageReader != null) {
                mImageReader.close();
                mImageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            mCameraOpenCloseLock.release();
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

    private Bitmap cropToCenterSquare(Bitmap bitmap) {
        int width  = bitmap.getWidth();
        int height = bitmap.getHeight();
        int newDim = Math.min(height, width);
        int cropW = (width - height) / 2;
        cropW = Math.max(cropW, 0);
        int cropH = (height - width) / 2;
        cropH = Math.max(cropH, 0);
        return Bitmap.createBitmap(bitmap, cropW, cropH, newDim, newDim);
    }

    private void finishedCaptureLocked() {
        try {
            // Reset the auto-focus trigger in case AF didn't run quickly enough.
            if (!mNoAFRun) {
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);

                mCameraCaptureSession.capture(mPreviewCaptureRequestBuilder.build(), mSessionCaptureCallback,
                        mBackgroundHandler);

                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private float getFingerSpacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

    private boolean hitTimeoutLocked() {
        return (SystemClock.elapsedRealtime() - mCaptureTimer) > PRECAPTURE_TIMEOUT_MS;
    }

    private boolean isNotLegacyLocked() {
        if (mMobileDevice.getCameraCharacteristics() == null) {
            return true;
        }
        Integer hardwareLevel = mMobileDevice.getCameraCharacteristics().get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (hardwareLevel == null) {
            return true;
        } else return hardwareLevel != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0) {
                for (int result : grantResults) {
                    if (result != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(getApplicationContext(), "Permissions not granted", Toast.LENGTH_SHORT).show();
                        finishAffinity();
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        setupCamera();
        CameraManager manager = mMobileDevice.getManager();
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            assert manager != null;
            manager.openCamera(mMobileDevice.getCameraId(), mCameraDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    private Bitmap prepareBitmap() {
        if (!mTextureView.isAvailable() || mCameraDevice == null) {
            return null;
        }

        Bitmap bitmap;
        Rational rational = mMobileDevice.getRatio();
        float ratio = (float) rational.getNumerator() / rational.getDenominator();
        if (ratio < 1.5) {
            // 1:1 or 4:3 --> scale
            bitmap = mTextureView.getBitmap(mClassifier.getImageSizeX(), mClassifier.getImageSizeY());
            assert bitmap != null;
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), mTextureView.getTransform(null), true);
        } else {
            // 16:9 or higher --> crop
            int width = mFinalSize.getWidth();
            int height = mFinalSize.getHeight();
            int w = (height > width) ? mClassifier.getImageSizeX() : width * mClassifier.getImageSizeY() / height;
            int h = (height > width) ? height * mClassifier.getImageSizeX() / width : mClassifier.getImageSizeY();
            bitmap = mTextureView.getBitmap(w, h);
            assert bitmap != null;
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), mTextureView.getTransform(null), true);
            bitmap = cropToCenterSquare(bitmap);
        }
        return bitmap;
    }

    private void prepareForCapture() {
        if (mState != STATE_PREVIEW) {
            return;
        }
        try {
            // Trigger an auto-focus run if camera is capable. If the camera is already focused, this should do nothing.
            if (!mNoAFRun) {
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_START);
            }
            // If this is not a legacy device, we can also trigger an auto-exposure metering run.
            if (isNotLegacyLocked()) {
                // Tell the camera to lock focus.
                mPreviewCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                        CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            }
            // Update state machine to wait for auto-focus, auto-exposure, and auto-white-balance (aka. "3A") to converge.
            mState = STATE_WAITING_FOR_3A_CONVERGENCE;
            // Start a timer for the pre-capture sequence.
            startTimerLocked();
            // Replace the existing repeating request with one with updated 3A triggers.
            mCameraCaptureSession.capture(mPreviewCaptureRequestBuilder.build(), mSessionCaptureCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void setup3AControlsAndZoom(CaptureRequest.Builder builder) {

        // Enable auto-magical 3A run by camera device
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

        Float minFocusDist = mMobileDevice.getCameraCharacteristics().get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        // If MINIMUM_FOCUS_DISTANCE is 0, lens is fixed-focus and we need to skip the AF run.
        mNoAFRun = (minFocusDist == null || minFocusDist == 0);

        if (!mNoAFRun) {
            // If there is a "continuous picture" mode available, use it, otherwise default to AUTO.
            if (contains(mMobileDevice.getCameraCharacteristics().get(
                    CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_AUTO);
            }
        }

        // If there is an auto-magical flash control mode available, use it, otherwise default to
        // the "on" mode, which is guaranteed to always be available.
        if (mMobileDevice.isFlashAvailable()) {
            int flashMode = mMobileDevice.getFlashMode();
            switch(flashMode) {
                case AUTO: {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    break;
                }
                case AUTO_RED_EYE: {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
                    break;
                }
                case SINGLE: {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);
                    break;
                }
                case OFF: {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                    break;
                }
                case TORCH: {
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                    builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                    break;
                }
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        }

        // If there is an auto-magical white balance control mode available, use it.
        if (contains(mMobileDevice.getCameraCharacteristics().get(
                CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES),
                CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
            // Allow AWB to run auto-magically if this device supports this
            builder.set(CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }

        //Zoom
        if (mZoom != null) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, mZoom);
        }
    }

    private void setupCamera() {

        // for capturing and saving an image
        Size largestImageSize = mMobileDevice.getLargestSize();
        mImageReader = ImageReader.newInstance(
                largestImageSize.getWidth(),
                largestImageSize.getHeight(),
                ImageFormat.JPEG,
                1);
        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);

        // for preview
        Point fullDisplaySize = new Point();
        getWindowManager().getDefaultDisplay().getRealSize(fullDisplaySize);

        int maxPreviewWidth = Resources.getSystem().getDisplayMetrics().widthPixels;
        int maxPreviewHeight = Resources.getSystem().getDisplayMetrics().heightPixels;
        if (maxPreviewWidth > MAX_PREVIEW_WIDTH) {
            maxPreviewWidth = MAX_PREVIEW_WIDTH;
        }
        if (maxPreviewHeight > MAX_PREVIEW_HEIGHT) {
            maxPreviewHeight = MAX_PREVIEW_HEIGHT;
        }

        Size[] choices = mMobileDevice.getOutputSizes();
        mPreviewSize = mMobileDevice.chooseOptimalSize(choices, maxPreviewWidth, maxPreviewHeight, largestImageSize);

        Rational preferredRatio = mMobileDevice.getRatio();
        ConstraintLayout constraintLayout = findViewById(R.id.constraintLayout);
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(constraintLayout);

        int w;
        int h;
        float d = (float) 1.0 * fullDisplaySize.y / fullDisplaySize.x;
        float c = (float) 1.0 * preferredRatio.getNumerator() / preferredRatio.getDenominator();
        if (d == c) {
            // full screen
            w = fullDisplaySize.x;
            h = fullDisplaySize.y;
            constraintSet.clear(R.id.constraintLayoutSmall, ConstraintSet.TOP);
            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
            constraintSet.setMargin(R.id.constraintLayoutSmall, ConstraintSet.BOTTOM, (int) px);
        } else {
            // not full screen
            if (preferredRatio.getNumerator() == 16) {
                constraintSet.connect(R.id.textureView, ConstraintSet.BOTTOM, R.id.constraintLayout, ConstraintSet.TOP,0);
                constraintSet.setMargin(R.id.constraintLayoutSmall, ConstraintSet.BOTTOM, 0);
                w = fullDisplaySize.x;
                h = fullDisplaySize.x * 16 / 9;
            } else if (preferredRatio.getNumerator() == 4) {
                w = fullDisplaySize.x;
                h = fullDisplaySize.x * 4 / 3;
            } else {
                w = fullDisplaySize.x;
                h = fullDisplaySize.x;
            }
            constraintSet.connect(R.id.constraintLayoutSmall, ConstraintSet.TOP, R.id.textureView, ConstraintSet.BOTTOM,0);
        }
        constraintSet.applyTo(constraintLayout);

        ConstraintLayout.LayoutParams params = new ConstraintLayout.LayoutParams(w, h);
        mTextureView.setLayoutParams(params);

        mFinalSize = new Size(w, h);
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera background thread");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mBackgroundHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mClassifier = new Classifier(CameraActivity.this);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
        mRunClassifier = true;
        mBackgroundHandler.post(mPeriodicClassify);
    }

    private void startCameraPreview() throws CameraAccessException {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        assert surfaceTexture != null;
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        mPreviewCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        mPreviewCaptureRequestBuilder.addTarget(previewSurface);
        mCameraDevice.createCaptureSession(
                Arrays.asList(previewSurface, mImageReader.getSurface()),
                mPreviewSessionCallback,
                mBackgroundHandler);
    }

    private void startTimerLocked() {
        mCaptureTimer = SystemClock.elapsedRealtime();
    }

    private void stopBackgroundThread() throws InterruptedException {
        mBackgroundThread.quitSafely();
        mBackgroundThread.join();
        mBackgroundThread = null;
        mBackgroundHandler = null;
        mRunClassifier = false;
    }

}