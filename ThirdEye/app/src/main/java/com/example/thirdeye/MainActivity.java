package com.example.thirdeye;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
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

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

    private static final String SERVER_URL = "http://10.10.189.237:8000/api/predict/";
    private static final String TAG = "ThirdEye";

    private PreviewView previewView;
    private TextView captionTextView;
    private TextToSpeech tts;
    private ExecutorService cameraExecutor;

    private boolean isTextReadingMode = false;
    private Vibrator vibrator;
    private TextRecognizer textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private boolean isTtsReady = false;

    // Thread-safe locks
    private volatile boolean isProcessing = false;
    private volatile String currentUtteranceId = "";
    private volatile boolean isFetchingLocation = false;

    // Touch Logic Variables
    private boolean isHolding = false;
    private boolean ignoreNextUp = false;
    private int tapCount = 0;
    private Handler tapHandler = new Handler(Looper.getMainLooper());
    private Runnable tapResetRunnable = () -> tapCount = 0;

    // Anti-Spam variables
    private String lastReadText = "";
    private long lastReadTime = 0;
    private String lastSceneText = "";
    private long lastSceneTime = 0;

    // Voice Command & Shake Variables
    private SensorManager sensorManager;
    private float accel;
    private float accelCurrent;
    private float accelLast;
    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;
    private boolean isListening = false;
    private long lastShakeTime = 0;

    // Location & Search Variables
    private FusedLocationProviderClient fusedLocationClient;
    private String searchTarget = "";

    // SharedPreferences for SOS Contact
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "ThirdEyePrefs";
    private static final String KEY_SOS_NUMBER = "sos_contact_number";

    private final ActivityResultLauncher<String[]> requestPermissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                Boolean cameraGranted = permissions.getOrDefault(Manifest.permission.CAMERA, false);
                Boolean audioGranted = permissions.getOrDefault(Manifest.permission.RECORD_AUDIO, false);
                Boolean smsGranted = permissions.getOrDefault(Manifest.permission.SEND_SMS, false);

                if (cameraGranted != null && cameraGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show();
                }

                if (audioGranted == null || !audioGranted) {
                    Toast.makeText(this, "Microphone permission needed", Toast.LENGTH_LONG).show();
                }

                if (smsGranted == null || !smsGranted) {
                    Toast.makeText(this, "SMS permission needed for SOS features", Toast.LENGTH_LONG).show();
                }
            });

    // Contact Picker Result Launcher
    private final ActivityResultLauncher<Intent> pickContactLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri contactUri = result.getData().getData();
                    String[] projection = new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER};

                    try (Cursor cursor = getContentResolver().query(contactUri, projection, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                            String number = cursor.getString(numberIndex);
                            number = number.replaceAll("[^0-9+]", "");
                            prefs.edit().putString(KEY_SOS_NUMBER, number).apply();
                            announceLocation("Emergency contact saved successfully.");
                        } else {
                            announceLocation("Could not read that contact.");
                        }
                    } catch (Exception e) {
                        announceLocation("Failed to read contact.");
                    }
                } else {
                    announceLocation("Contact selection cancelled.");
                }
                isProcessing = false;
                isFetchingLocation = false;
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        captionTextView = findViewById(R.id.captionTextView);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accel = 10f;
        accelCurrent = SensorManager.GRAVITY_EARTH;
        accelLast = SensorManager.GRAVITY_EARTH;

        setupSpeechRecognizer();

        View cameraView = findViewById(R.id.previewView);

        cameraView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                tapCount++;
                tapHandler.removeCallbacks(tapResetRunnable);
                if (tapCount == 3) {
                    tapCount = 0;
                    if (isTextReadingMode) switchToSceneMode();
                    else switchToTextMode();
                } else {
                    ignoreNextUp = false;
                    tapHandler.postDelayed(tapResetRunnable, 400);
                    isHolding = true;
                    if (isTextReadingMode) {
                        vibratePhone(50);
                        updateUIOnly("Reading Text...");
                    } else {
                        if (tts != null) tts.stop();
                        vibratePhone(50);
                        updateUIOnly("Paused Scene Description.");
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                if (ignoreNextUp) {
                    ignoreNextUp = false;
                    return true;
                }
                isHolding = false;
                if (isFetchingLocation) return true;
                if (isTextReadingMode) {
                    if (tts != null) tts.stop();
                    lastReadText = "";
                    isProcessing = false;
                    updateUIOnly("Text Reading Mode (Tap and hold to read)");
                } else {
                    lastSceneText = "";
                    isProcessing = false;
                    if (!searchTarget.isEmpty()) updateUIOnly("Scanning for: " + searchTarget + "...");
                    else updateUIOnly("Resuming Scene Description...");
                }
            }
            return true;
        });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        cameraExecutor = Executors.newSingleThreadExecutor();

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.US);
                if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isTtsReady = true;
                    tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {}
                        @Override public void onDone(String utteranceId) {
                            if (utteranceId != null && utteranceId.equals(currentUtteranceId)) {
                                isProcessing = false;
                                isFetchingLocation = false;
                            }
                        }
                        @Override public void onError(String utteranceId) {
                            if (utteranceId != null && utteranceId.equals(currentUtteranceId)) {
                                isProcessing = false;
                                isFetchingLocation = false;
                            }
                        }
                        @Override public void onStop(String utteranceId, boolean interrupted) {
                            if (utteranceId != null && utteranceId.equals(currentUtteranceId)) {
                                isProcessing = false;
                                isFetchingLocation = false;
                            }
                        }
                    });
                }
            }
        });

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionsLauncher.launch(new String[]{
                    Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.SEND_SMS
            });
        }
    }

    private void switchToTextMode() {
        if (isTextReadingMode) return;
        isTextReadingMode = true; isHolding = false; isProcessing = true;
        isFetchingLocation = false; ignoreNextUp = true; lastReadText = "";
        lastSceneText = ""; searchTarget = "";
        if (tts != null) tts.stop();
        vibratePhone(300);
        String msg = "Text reading mode activated. Tap and hold to read text.";
        updateUIOnly(msg);
        if (isTtsReady && tts != null) {
            currentUtteranceId = "TTS_" + System.currentTimeMillis();
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
        } else isProcessing = false;
    }

    private void switchToSceneMode() {
        if (!isTextReadingMode && searchTarget.isEmpty()) return;
        isTextReadingMode = false; isHolding = false; isProcessing = true;
        isFetchingLocation = false; ignoreNextUp = true; lastReadText = "";
        lastSceneText = ""; searchTarget = "";
        if (tts != null) tts.stop();
        vibratePhone(300);
        String msg = "Scene description mode active.";
        updateUIOnly(msg);
        if (isTtsReady && tts != null) {
            currentUtteranceId = "TTS_" + System.currentTimeMillis();
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
        } else isProcessing = false;
    }

    private void switchToFindMode(String item) {
        isTextReadingMode = false; isHolding = false; isProcessing = true;
        isFetchingLocation = false; ignoreNextUp = true; lastSceneText = "";
        searchTarget = item;
        if (tts != null) tts.stop();
        vibratePhone(300);
        String msg = "Find mode active. Scanning for " + item + ".";
        updateUIOnly(msg);
        if (isTtsReady && tts != null) {
            currentUtteranceId = "TTS_" + System.currentTimeMillis();
            tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
        } else isProcessing = false;
    }

    private void getLocationAndSpeak() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            announceLocation("Location permission is needed for this feature.");
            return;
        }
        updateUIOnly("Pinpointing location...");
        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken())
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        Executors.newSingleThreadExecutor().execute(() -> {
                            try {
                                Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                                if (addresses != null && !addresses.isEmpty()) {
                                    announceLocation("You are currently near " + addresses.get(0).getAddressLine(0));
                                } else announceLocation("Coordinates found, but street name is unknown.");
                            } catch (IOException e) { announceLocation("Network too slow to determine street name."); }
                        });
                    } else announceLocation("GPS signal lost. Try stepping near a window.");
                })
                .addOnFailureListener(e -> announceLocation("Failed to get location hardware access."));
    }

    private void triggerSOS() {
        String savedNumber = prefs.getString(KEY_SOS_NUMBER, "");
        if (savedNumber.isEmpty()) {
            announceLocation("No emergency contact set. Please say 'Set Emergency Contact' first.");
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            announceLocation("Location permission needed to send an SOS.");
            return;
        }
        vibratePhone(500); updateUIOnly("Initiating SOS protocol...");
        CancellationTokenSource cancellationTokenSource = new CancellationTokenSource();
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.getToken())
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        Executors.newSingleThreadExecutor().execute(() -> {
                            try {
                                Geocoder geocoder = new Geocoder(MainActivity.this, Locale.getDefault());
                                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                                String locationText = (addresses != null && !addresses.isEmpty()) ? addresses.get(0).getAddressLine(0) : "an unknown street";
                                String googleMapsLink = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                                String message = "EMERGENCY: I need help! My current location is " + locationText + ". " + googleMapsLink;
                                sendAutomatedSMS(message, savedNumber);
                            } catch (IOException e) {
                                String googleMapsLink = "https://maps.google.com/?q=" + location.getLatitude() + "," + location.getLongitude();
                                sendAutomatedSMS("EMERGENCY: I need help! My coordinates are: " + location.getLatitude() + ", " + location.getLongitude() + ". " + googleMapsLink, savedNumber);
                            }
                        });
                    } else announceLocation("GPS signal lost. Cannot attach location.");
                })
                .addOnFailureListener(e -> announceLocation("Failed to get location access."));
    }

    private void sendAutomatedSMS(String message, String phoneNumber) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            announceLocation("SMS permission required.");
            return;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                SmsManager smsManager = SmsManager.getDefault();
                ArrayList<String> parts = smsManager.divideMessage(message);
                smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null);
                announceLocation("Emergency message sent successfully.");
            } catch (Exception e) { announceLocation("Failed to send message."); }
        });
    }

    private void startWalkingNavigation(String destination) {
        vibratePhone(300);
        announceLocation("Starting walking directions to " + destination);
        Uri gmmIntentUri = Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=w");
        Intent mapIntent = new Intent(Intent.ACTION_VIEW, gmmIntentUri);
        mapIntent.setPackage("com.google.android.apps.maps");

        if (mapIntent.resolveActivity(getPackageManager()) != null) {
            startActivity(mapIntent);
        } else {
            announceLocation("Google Maps is not installed on this device.");
        }
    }

    private void announceLocation(String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            captionTextView.setText(text);
            if (isTtsReady && tts != null) {
                currentUtteranceId = "TTS_LOC_" + System.currentTimeMillis();
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
            } else { isProcessing = false; isFetchingLocation = false; }
        });
    }

    private void setupSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                vibratePhone(150); new Handler(Looper.getMainLooper()).postDelayed(() -> vibratePhone(150), 250);
                updateUIOnly("Listening...");
            }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() { isListening = false; }
            @Override public void onError(int error) {
                isListening = false;
                updateUIOnly(searchTarget.isEmpty() ? (isTextReadingMode ? "Text Reading Mode" : "Scene Description Mode") : "Scanning for: " + searchTarget);
            }
            @Override public void onResults(Bundle results) {
                isListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                boolean commandFound = false;
                if (matches != null) {
                    for (String match : matches) {
                        String command = match.toLowerCase();

                        if (command.startsWith("directions to") || command.startsWith("navigate to")) {
                            commandFound = true;
                            String dest = command.replace("directions to", "").replace("navigate to", "").trim();
                            if (!dest.isEmpty()) startWalkingNavigation(dest);
                            else announceLocation("Please specify a destination.");
                            break;
                        }
                        else if (command.contains("set emergency") || command.contains("set contact")) {
                            commandFound = true; isFetchingLocation = true; isProcessing = true;
                            if (tts != null) tts.stop();
                            new Handler(Looper.getMainLooper()).post(() -> {
                                announceLocation("Opening contacts.");
                                Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
                                try { pickContactLauncher.launch(intent); } catch (Exception e) {
                                    announceLocation("Contacts app not found."); isProcessing = false; isFetchingLocation = false;
                                }
                            }); break;
                        }
                        else if (command.contains("emergency") || command.contains("help") || command.contains("sos")) {
                            commandFound = true; isFetchingLocation = true; isProcessing = true;
                            if (tts != null) tts.stop(); triggerSOS(); break;
                        }
                        else if (command.contains("where am i") || command.contains("location")) {
                            commandFound = true; isFetchingLocation = true; isProcessing = true;
                            if (tts != null) tts.stop(); getLocationAndSpeak(); break;
                        }
                        else if (command.startsWith("find ") || command.startsWith("look for ")) {
                            commandFound = true;
                            String item = command.replace("find ", "").replace("look for ", "").replace("the ", "").replace("my ", "").replace("a ", "").trim();
                            switchToFindMode(item); break;
                        }
                        else if (command.contains("switch") || command.contains("change") || command.contains("toggle")) {
                            if (isTextReadingMode) switchToSceneMode(); else switchToTextMode();
                            commandFound = true; break;
                        }
                        else if (command.contains("text") || command.contains("read")) { switchToTextMode(); commandFound = true; break; }
                        else if (command.contains("scene") || command.contains("describe")) { switchToSceneMode(); commandFound = true; break; }
                    }
                }
                if (!commandFound && isTtsReady && tts != null) {
                    currentUtteranceId = "TTS_" + System.currentTimeMillis();
                    tts.speak("Command not recognized.", TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });
    }

    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent event) {
            float x = event.values[0], y = event.values[1], z = event.values[2];
            accelLast = accelCurrent;
            accelCurrent = (float) Math.sqrt((double) (x * x + y * y + z * z));
            accel = accel * 0.9f + (accelCurrent - accelLast);
            long currentTime = System.currentTimeMillis();
            if (accel > 12 && (currentTime - lastShakeTime) > 2000) {
                lastShakeTime = currentTime;
                if (!isListening) {
                    isListening = true; if (tts != null) tts.stop();
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        try { speechRecognizer.startListening(speechRecognizerIntent); } catch (Exception e) { isListening = false; }
                    }, 300);
                }
            }
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    @Override protected void onResume() { super.onResume(); sensorManager.registerListener(sensorListener, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL); }
    @Override protected void onPause() { sensorManager.unregisterListener(sensorListener); super.onPause(); }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    if (isProcessing || isListening || isFetchingLocation || (tts != null && tts.isSpeaking())) {
                        imageProxy.close(); return;
                    }
                    if (isTextReadingMode) {
                        if (isHolding) { isProcessing = true; processImageForText(imageProxy); } else imageProxy.close();
                    } else {
                        if (!isHolding) { isProcessing = true; processImage(imageProxy); } else imageProxy.close();
                    }
                });
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) { Log.e(TAG, "Binding failed", e); }
        }, ContextCompat.getMainExecutor(this));
    }

    @androidx.annotation.OptIn(markerClass = androidx.camera.core.ExperimentalGetImage.class)
    private void processImageForText(ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) { isProcessing = false; imageProxy.close(); return; }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        textRecognizer.process(image).addOnSuccessListener(visionText -> {
            String detectedText = visionText.getText().trim();
            if (!detectedText.isEmpty()) {
                long currentTime = System.currentTimeMillis();
                if (detectedText.equals(lastReadText) && (currentTime - lastReadTime) < 5000) { isProcessing = false; return; }
                lastReadText = detectedText; lastReadTime = currentTime;
                updateUIAndSpeak("I see text: " + detectedText, true);
            } else isProcessing = false;
        }).addOnCompleteListener(task -> imageProxy.close());
    }

    private void processImage(@NonNull ImageProxy imageProxy) {
        try {
            Bitmap bitmap = imageProxy.toBitmap();
            int rotationDegrees = imageProxy.getImageInfo().getRotationDegrees();
            if (rotationDegrees != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(rotationDegrees);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream);
            sendImageToServer(outputStream.toByteArray());
        } catch (Exception e) { isProcessing = false; }
        finally { imageProxy.close(); }
    }

    // --- UPDATED: Dynamic POST Request based on Radar Mode! ---
    private void sendImageToServer(byte[] imageBytes) {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", "frame.jpg", RequestBody.create(imageBytes, MediaType.parse("image/jpeg")));

        // If we are looking for something, tell Django to skip the heavy BLIP model!
        if (!searchTarget.isEmpty()) {
            builder.addFormDataPart("mode", "radar");
        }

        RequestBody requestBody = builder.build();

        Request request = new Request.Builder()
                .url(SERVER_URL)
                .post(requestBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { if (!isFetchingLocation) updateUIAndSpeak("Server error.", false); }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (isFetchingLocation) { if (response.body() != null) response.body().close(); return; }

                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String responseData = response.body().string();
                        JSONObject json = new JSONObject(responseData);

                        if (!searchTarget.isEmpty()) {
                            // RADAR MODE: Only check the high-speed YOLO objects bucket
                            boolean found = false;
                            String targetDirection = searchTarget;

                            if (json.has("objects")) {
                                org.json.JSONArray objects = json.getJSONArray("objects");
                                for (int i = 0; i < objects.length(); i++) {
                                    String obj = objects.getString(i).toLowerCase();
                                    if (obj.contains(searchTarget.toLowerCase()) || searchTarget.toLowerCase().contains(obj)) {
                                        found = true;
                                        break;
                                    }
                                }
                            }

                            if (found) {
                                // Find the exact spatial direction!
                                if (json.has("directions_list")) {
                                    org.json.JSONArray dirs = json.getJSONArray("directions_list");
                                    for (int i = 0; i < dirs.length(); i++) {
                                        String dir = dirs.getString(i).toLowerCase();
                                        if (dir.contains(searchTarget.toLowerCase()) || searchTarget.toLowerCase().contains(dir.split(" ")[0])) {
                                            targetDirection = dirs.getString(i);
                                            break;
                                        }
                                    }
                                }
                                vibratePhone(500);
                                announceTargetFound(targetDirection);
                            } else {
                                isProcessing = false;
                                updateUIOnly("Scanning for " + searchTarget + "...");
                            }
                        } else {
                            // NORMAL SCENE MODE: Combine BLIP and YOLO buckets
                            String caption = json.optString("caption", "");
                            String distance = json.optString("distance", "");
                            String spatial = json.optString("spatial", "");

                            String fullScene = caption;
                            if (!distance.isEmpty() || !spatial.isEmpty()) {
                                fullScene += ". " + distance + spatial;
                            }
                            updateUIAndSpeak(fullScene, false);
                        }
                    } catch (JSONException e) { isProcessing = false; }
                } else isProcessing = false;

                if (response.body() != null) response.body().close();
            }
        });
    }

    // --- UPDATED: Short and punchy radar announcements ---
    private void announceTargetFound(String targetDirection) {
        new Handler(Looper.getMainLooper()).post(() -> {
            String msg = "Target found. " + targetDirection;

            lastSceneText = msg;
            lastSceneTime = System.currentTimeMillis();
            searchTarget = ""; // Flips back to normal mode for the next frame

            captionTextView.setText(msg);
            if (isTtsReady && tts != null) {
                currentUtteranceId = "TTS_" + System.currentTimeMillis();
                tts.speak(msg, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
            } else isProcessing = false;
        });
    }

    private void updateUIAndSpeak(String caption, boolean isFromTextMode) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isListening || isFetchingLocation) return;
            if (isFromTextMode != isTextReadingMode || (!isTextReadingMode && isHolding) || (isTextReadingMode && !isHolding)) { isProcessing = false; return; }
            long currentTime = System.currentTimeMillis();
            if (!isFromTextMode && caption.equals(lastSceneText) && (currentTime - lastSceneTime) < 6000) { isProcessing = false; return; }
            if (!isFromTextMode) { lastSceneText = caption; lastSceneTime = currentTime; }
            captionTextView.setText(caption);
            if (isTtsReady && tts != null) {
                currentUtteranceId = "TTS_" + currentTime;
                tts.speak(caption, TextToSpeech.QUEUE_FLUSH, null, currentUtteranceId);
            } else isProcessing = false;
        });
    }

    private void updateUIOnly(String caption) { new Handler(Looper.getMainLooper()).post(() -> captionTextView.setText(caption)); }
    private void vibratePhone(int dur) { if (vibrator != null && vibrator.hasVibrator()) { if (android.os.Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createOneShot(dur, -1)); else vibrator.vibrate(dur); } }

    @Override protected void onDestroy() { if (tts != null) { tts.stop(); tts.shutdown(); } if (cameraExecutor != null) cameraExecutor.shutdown(); if (speechRecognizer != null) speechRecognizer.destroy(); super.onDestroy(); }
}