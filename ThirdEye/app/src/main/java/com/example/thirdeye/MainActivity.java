package com.example.thirdeye;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    // IMPORTANT: Make sure this IP matches your laptop exactly
    private static final String SERVER_URL = "http://192.168.1.90:8000/api/predict/";
    private static final String TAG = "ThirdEye";
    private static final long HEARTBEAT_INTERVAL_MS = 3000;

    private PreviewView previewView;
    private TextView captionTextView;
    private TextToSpeech tts;
    private ExecutorService cameraExecutor;

    // 1. We create ONE client with the 60-second rules
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private long lastAnalyzedTimestamp = 0;
    private boolean isTtsReady = false;
    private boolean isProcessing = false;

    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        captionTextView = findViewById(R.id.captionTextView);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // 2. REMOVED the duplicate "httpClient = new OkHttpClient();" line that was causing the timeout bug

        cameraExecutor = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS Language not supported");
                } else {
                    isTtsReady = true;

                    // NEW: Tell Android to unlock the camera when it finishes speaking
                    tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {}

                        @Override
                        public void onDone(String utteranceId) {
                            isProcessing = false; // Unlock! Ready for next picture
                        }

                        @Override
                        public void onError(String utteranceId) {
                            isProcessing = false; // Unlock if there's an error
                        }
                    });
                }
            } else {
                Log.e(TAG, "TTS Initialization failed");
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                // THE HEARTBEAT LOOP
                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    // Only take a picture if we are NOT currently processing/speaking
                    if (!isProcessing) {
                        isProcessing = true; // Lock the camera!
                        processImage(imageProxy);
                    } else {
                        imageProxy.close(); // Throw frame away if we are busy
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processImage(@NonNull ImageProxy imageProxy) {
        try {
            // Android 14+ specific: .toBitmap() works natively here
            Bitmap bitmap = imageProxy.toBitmap();

            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            if (rotationDegrees != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotationDegrees);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream);
            byte[] jpegBytes = outputStream.toByteArray();

            sendImageToServer(jpegBytes);
        } catch (Exception e) {
            Log.e(TAG, "Failed to process image", e);
        } finally {
            imageProxy.close(); // Crucial to prevent memory leaks!
        }
    }

    private void sendImageToServer(byte[] imageBytes) {
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg",
                        RequestBody.create(imageBytes, MediaType.parse("image/jpeg")))
                .build();

        Request request = new Request.Builder()
                .url(SERVER_URL)
                .post(requestBody)
                .build();

        // 3. We are now using the correct 60-second 'client' here!
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Network request failed", e);
                // Print to screen if network fails
                updateUIAndSpeak("Cannot connect to server.");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JSONObject json = new JSONObject(responseData);

                        // Look for our specific JSON key from Django
                        if (json.has("caption")) {
                            String caption = json.getString("caption");
                            updateUIAndSpeak(caption);
                        }
                    } catch (JSONException e) {
                        Log.e(TAG, "JSON parsing error", e);
                    }
                } else {
                    Log.e(TAG, "Server returned error: " + response.code());
                }

                // Always close the body to prevent OkHttp crashes
                if (response.body() != null) {
                    response.body().close();
                }
            }
        });
    }

            private void updateUIAndSpeak(String caption) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    captionTextView.setText(caption);

                    if (isTtsReady && tts != null) {
                        // The "CaptionID" at the end tells the listener when the speech finishes
                        tts.speak(caption, TextToSpeech.QUEUE_FLUSH, null, "CaptionID");
                    } else {
                        isProcessing = false; // Unlock if TTS fails
                    }
                });
            }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        super.onDestroy();
    }
}