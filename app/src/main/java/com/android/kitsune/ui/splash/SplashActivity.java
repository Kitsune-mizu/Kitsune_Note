package com.android.kitsune.ui.splash;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import com.android.kitsune.R;
import com.android.kitsune.base.BaseActivity;
import com.android.kitsune.data.session.UserSession;
import com.android.kitsune.ui.auth.LoginActivity;
import com.android.kitsune.ui.main.MainActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.SettingsClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends BaseActivity {

    // ─── Constants ───────────────────────────────────────────────────────────

    private static final String TAG = "SplashActivity";
    private static final int LOCATION_PERMISSION_REQUEST = 101;
    private static final int REQUEST_CHECK_SETTINGS = 102;

    // ─── Views ───────────────────────────────────────────────────────────────

    private WebView splashWebView;
    private LinearLayout greetingContainer;
    private ImageView imgFlag;
    private TextView tvGreeting;

    // ─── Location ────────────────────────────────────────────────────────────

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;

    // ─── State ───────────────────────────────────────────────────────────────

    private String lastCountry = "";
    private boolean isFlagLoaded = false;
    private boolean isGreetingReady = false;
    private boolean isDestroyed = false;        // ← guard lifecycle

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(); // ← ganti raw Thread


    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        UserSession.init(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initViews();

        // ── Muat HTML SVG dari Assets di background ──────────────
        String[] themeColors = resolveThemeColors();
        executor.execute(() -> {
            // 1. Baca HTML mentah dari folder assets
            String rawHtml = loadHtmlFromAsset();

            // 2. Ganti placeholder dengan warna tema yang aktif
            String finalHtml = rawHtml
                    .replace("{{cMid}}", themeColors[0])
                    .replace("{{cAccent}}", themeColors[1])
                    .replace("{{cAccentLight}}", themeColors[2])
                    .replace("{{cBorder}}", themeColors[3]);

            // 3. Render ke WebView di Main Thread
            mainHandler.post(() -> {
                if (!isDestroyed) loadWebView(finalHtml, themeColors[4]);
            });
        });

        mainHandler.postDelayed(this::checkLocationPermission, 3000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isDestroyed = true;
        executor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }
        // Destroy WebView dengan benar agar tidak leak
        if (splashWebView != null) {
            splashWebView.stopLoading();
            splashWebView.destroy();
        }
    }


    // ─── Initialization ──────────────────────────────────────────────────────

    private void initViews() {
        splashWebView    = findViewById(R.id.splashWebView);
        greetingContainer = findViewById(R.id.greetingContainer);
        imgFlag          = findViewById(R.id.imgFlag);
        tvGreeting       = findViewById(R.id.tvGreeting);
        TextView tvVersion = findViewById(R.id.tvVersion);

        if (tvVersion != null) {
            try {
                String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                tvVersion.setText(getString(R.string.splash_version_format, v));
            } catch (Exception ignored) {}
        }
    }

    /**
     * Resolve warna tema sekali saja — dijalankan di main thread sebelum
     * pekerjaan HTML diserahkan ke background.
     * Mengembalikan array: [cMid, cAccent, cAccentLight, cBorder, cTextUi]
     */
    private String[] resolveThemeColors() {
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        String themeMode  = prefs.getString("theme_mode", "system");
        String colorTheme = prefs.getString("color_theme", "blue");

        boolean isDark;
        if      (themeMode.equals("dark"))  isDark = true;
        else if (themeMode.equals("light")) isDark = false;
        else {
            int nightMode = getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }

        String cTextUi = isDark ? "#F1F5F9" : "#1E293B";
        String cMid, cAccent, cAccentLight, cBorder;

        if (isDark) {
            cMid = "#1E293B"; cAccent = "#475569"; cAccentLight = "#334155"; cBorder = "#F1F5F9";
        } else {
            switch (colorTheme) {
                case "purple": cMid="#F3F0FF"; cAccent="#A78BFA"; cAccentLight="#DDD6FE"; cBorder="#1E293B"; break;
                case "pink":   cMid="#FCE7F3"; cAccent="#F9A8D4"; cAccentLight="#FBCFE8"; cBorder="#1E293B"; break;
                case "green":  cMid="#ECFDF5"; cAccent="#6EE7B7"; cAccentLight="#A7F3D0"; cBorder="#1E293B"; break;
                case "orange": cMid="#FFF7ED"; cAccent="#FB923C"; cAccentLight="#FED7AA"; cBorder="#1E293B"; break;
                default:       cMid="#E8F4FD"; cAccent="#7BA7D8"; cAccentLight="#B8D4F1"; cBorder="#1E293B"; break;
            }
        }
        return new String[]{cMid, cAccent, cAccentLight, cBorder, cTextUi};
    }

    /** Membaca file splash_anim.html dari folder assets */
    private String loadHtmlFromAsset() {
        try {
            InputStream is = getAssets().open("splash_anim.html");
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Gagal memuat file SVG dari asset", e);
            return "";
        }
    }

    /** Dipanggil dari main thread setelah HTML siap dari background */
    @SuppressLint("SetJavaScriptEnabled")
    private void loadWebView(String html, String cTextUi) {
        int colorInt = Color.parseColor(cTextUi);
        tvGreeting.setTextColor(colorInt);

        WebSettings ws = splashWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        splashWebView.setBackgroundColor(Color.TRANSPARENT);
        splashWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }


    // ─── Navigation ──────────────────────────────────────────────────────────

    private void goNext() {
        if (isDestroyed) return;
        if (fusedLocationClient != null && locationCallback != null)
            fusedLocationClient.removeLocationUpdates(locationCallback);

        startActivity(new Intent(this,
                UserSession.getInstance().isLoggedIn() ? MainActivity.class : LoginActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }


    // ─── Flag & Greeting ─────────────────────────────────────────────────────

    private void preloadFlagAndGreeting(String countryCode) {
        isFlagLoaded   = false;
        isGreetingReady = false;

        tvGreeting.setText(getManualGreeting(countryCode));
        isGreetingReady = true;

        String flagUrl = "https://flagcdn.com/w320/" + countryCode + ".png";
        int size = 200;
        RequestOptions options = new RequestOptions()
                .transform(new com.bumptech.glide.load.MultiTransformation<>(
                        new com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                        new RoundedCorners(24)
                ));

        Glide.with(this)
                .load(flagUrl)
                .apply(options)
                .listener(new com.bumptech.glide.request.RequestListener<>() {
                    @Override
                    public boolean onLoadFailed(
                            @androidx.annotation.Nullable com.bumptech.glide.load.engine.GlideException e,
                            Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            boolean isFirstResource) {
                        isFlagLoaded = true;
                        checkAndTransitionToPhase2();
                        return false;
                    }
                    @Override
                    public boolean onResourceReady(
                            android.graphics.drawable.Drawable resource,
                            Object model,
                            com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                            com.bumptech.glide.load.DataSource dataSource,
                            boolean isFirstResource) {
                        isFlagLoaded = true;
                        checkAndTransitionToPhase2();
                        return false;
                    }
                })
                .into(imgFlag);
    }

    private void checkAndTransitionToPhase2() {
        if (isFlagLoaded && isGreetingReady) mainHandler.post(this::transitionToPhase2);
    }

    private void transitionToPhase2() {
        if (isDestroyed) return;
        splashWebView.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            splashWebView.setVisibility(View.GONE);
            showGreetingSequence();
        }).start();
    }

    private void showGreetingSequence() {
        greetingContainer.setVisibility(View.VISIBLE);
        greetingContainer.setAlpha(0f);
        imgFlag.setVisibility(View.VISIBLE);
        imgFlag.setAlpha(0f);
        imgFlag.setScaleX(0.8f);
        imgFlag.setScaleY(0.8f);

        imgFlag.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(600)
                .withStartAction(() -> greetingContainer.animate().alpha(1f).setDuration(600).start())
                .withEndAction(this::showGreetingText).start();
    }

    private void showGreetingText() {
        tvGreeting.setVisibility(View.VISIBLE);
        tvGreeting.setAlpha(0f);
        tvGreeting.setTranslationY(20f);
        tvGreeting.animate().alpha(1f).translationY(0f).setDuration(500).setStartDelay(200)
                .withEndAction(() -> mainHandler.postDelayed(this::goNext, 2000)).start();
    }


    // ─── Location Permission ──────────────────────────────────────────────────

    private void checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST);
        } else {
            checkLocationSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkLocationSettings();
        } else {
            getNetworkLocationFallback();
        }
    }

    private void checkLocationSettings() {
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000)
                .setMinUpdateIntervalMillis(2000)
                .setWaitForAccurateLocation(true)
                .build();

        LocationSettingsRequest settingsRequest = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest).setAlwaysShow(true).build();

        SettingsClient client = LocationServices.getSettingsClient(this);
        client.checkLocationSettings(settingsRequest)
                .addOnSuccessListener(this, r -> startRealtimeLocationUpdates())
                .addOnFailureListener(this, e -> {
                    if (e instanceof ResolvableApiException) {
                        try { ((ResolvableApiException) e).startResolutionForResult(this, REQUEST_CHECK_SETTINGS); }
                        catch (Exception ex) { getNetworkLocationFallback(); }
                    } else { getNetworkLocationFallback(); }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == RESULT_OK) startRealtimeLocationUpdates();
            else getNetworkLocationFallback();
        }
    }

    @SuppressLint("MissingPermission")
    private void startRealtimeLocationUpdates() {
        if (fusedLocationClient == null) return;
        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(@NonNull LocationResult result) {
                Location loc = result.getLastLocation();
                if (loc != null) handleLocation(loc);
            }
        };
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
    }


    // ─── Geocoding ───────────────────────────────────────────────────────────

    private void handleLocation(Location location) {
        double lat = location.getLatitude(), lon = location.getLongitude();
        executor.execute(() -> {
            try {
                Geocoder geocoder = new Geocoder(SplashActivity.this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                mainHandler.post(() -> {
                    if (isDestroyed) return;
                    if (addresses != null && !addresses.isEmpty()) {
                        String countryName = addresses.get(0).getCountryName();
                        UserSession.getInstance().setDetectedCountryName(countryName != null ? countryName : "");
                        updateCountry(addresses.get(0).getCountryCode());
                    } else {
                        getNetworkLocationFallback();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Geocoder error: " + e.getMessage());
                mainHandler.post(() -> { if (!isDestroyed) getNetworkLocationFallback(); });
            }
        });
    }

    private void updateCountry(String countryCode) {
        if (countryCode == null) return;
        countryCode = countryCode.toLowerCase(Locale.ROOT);
        if (!countryCode.equals(lastCountry)) {
            lastCountry = countryCode;
            preloadFlagAndGreeting(countryCode);
        }
    }


    // ─── Fallback Location ────────────────────────────────────────────────────

    private void getNetworkLocationFallback() {
        executor.execute(() -> {
            try {
                String code = getCountryCodeFromIP();
                mainHandler.post(() -> {
                    if (!isDestroyed)
                        updateCountry(code.isEmpty() ? Locale.getDefault().getCountry() : code);
                });
            } catch (Exception e) {
                Log.e(TAG, "IP fallback failed: " + e.getMessage());
                mainHandler.post(() -> { if (!isDestroyed) useLocaleFallback(); });
            }
        });
    }

    private void useLocaleFallback() {
        updateCountry(Locale.getDefault().getCountry().toLowerCase());
    }

    private String getCountryCodeFromIP() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("https://ipapi.co/json/").openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);

        JSONObject json = new JSONObject(readResponse(connection));
        UserSession.getInstance().setDetectedCountryName(json.optString("country_name", ""));
        return json.optString("country_code", "").toLowerCase(Locale.ROOT);
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    // ─── Greeting Helper ─────────────────────────────────────────────────────

    private String getManualGreeting(String countryCode) {
        return switch (countryCode.toLowerCase()) {
            case "id" -> "Halo!";
            case "my" -> "Hai!";
            case "ph" -> "Kamusta!";
            case "th" -> "สวัสดี";
            case "vn" -> "Xin chào!";
            case "la" -> "ສະບາຍດີ";
            case "kh" -> "សួស្តី";
            case "mm" -> "မင်္ဂလာပါ";
            case "jp" -> "こんにちは";
            case "kr" -> "안녕하세요";
            case "cn", "tw", "hk" -> "你好";
            case "in" -> "नमस्ते";
            case "pk", "sa" -> "السلام عليكم";
            case "bd" -> "হ্যালো";
            case "ae", "eg", "ma", "dz" -> "مرحبا";
            case "il" -> "שלום";
            case "ir" -> "سلام";
            case "tr" -> "Merhaba!";
            case "fr" -> "Bonjour!";
            case "de", "nl", "be", "no" -> "Hallo!";
            case "es", "mx", "ar", "cl", "co", "ve", "pe" -> "¡Hola!";
            case "pt", "br" -> "Olá!";
            case "it" -> "Ciao!";
            case "ch" -> "Grüezi!";
            case "gr" -> "Γειά σου";
            case "se", "dk" -> "Hej!";
            case "fi" -> "Hei!";
            case "ru" -> "Привет!";
            case "ua" -> "Привіт!";
            case "cz", "sk" -> "Ahoj!";
            case "hu" -> "Szia!";
            case "ro" -> "Salut!";
            case "bg" -> "Здравей";
            case "pl" -> "Cześć!";
            case "ke" -> "Jambo!";
            case "et" -> "Selam!";
            default -> "Hello!";
        };
    }
}