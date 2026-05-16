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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

@SuppressLint("CustomSplashScreen")
public class SplashActivity extends BaseActivity {

    // ─── Constants & Variables ───────────────────────────────────────────────

    private static final String TAG = "SplashActivity";
    private static final int LOCATION_PERMISSION_REQUEST = 101;
    private static final int REQUEST_CHECK_SETTINGS = 102;

    private WebView splashWebView;
    private LinearLayout greetingContainer;
    private ImageView imgFlag;
    private TextView tvGreeting;

    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private LocationRequest locationRequest;

    private String lastCountry = "";
    private boolean isFlagLoaded = false;
    private boolean isGreetingReady = false;

    private final Handler handler = new Handler();


    // ─── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        UserSession.init(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        initViews();
        playSplashSequence();
    }


    // ─── Initialization ──────────────────────────────────────────────────────

    private void initViews() {
        splashWebView = findViewById(R.id.splashWebView);
        loadSplashSvg();

        String cTextUi = splashWebView.getTag() != null ? (String) splashWebView.getTag() : "#1E293B";

        greetingContainer = findViewById(R.id.greetingContainer);
        imgFlag = findViewById(R.id.imgFlag);
        tvGreeting = findViewById(R.id.tvGreeting);
        TextView tvVersion = findViewById(R.id.tvVersion);

        int colorInt = android.graphics.Color.parseColor(cTextUi);
        tvGreeting.setTextColor(colorInt);

        if (tvVersion != null) {
            tvVersion.setTextColor(colorInt);
            try {
                String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                tvVersion.setText(getString(R.string.splash_version_format, v));
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void loadSplashSvg() {
        SharedPreferences prefs = getSharedPreferences("app_settings", MODE_PRIVATE);
        String themeMode = prefs.getString("theme_mode", "system");
        String colorTheme = prefs.getString("color_theme", "blue");

        boolean isDark;
        if (themeMode.equals("dark")) {
            isDark = true;
        } else if (themeMode.equals("light")) {
            isDark = false;
        } else {
            int nightMode = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            isDark = nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }

        String cTextUi = isDark ? "#F1F5F9" : "#1E293B";
        splashWebView.setTag(cTextUi);

        String cMid, cAccent, cAccentLight, cBorder;

        if (isDark) {
            cMid = "#1E293B";
            cAccent = "#475569";
            cAccentLight = "#334155";
            cBorder = "#F1F5F9";
        } else {
            switch (colorTheme) {
                case "purple":
                    cMid = "#F3F0FF";
                    cAccent = "#A78BFA";
                    cAccentLight = "#DDD6FE";
                    cBorder = "#1E293B";
                    break;
                case "pink":
                    cMid = "#FCE7F3";
                    cAccent = "#F9A8D4";
                    cAccentLight = "#FBCFE8";
                    cBorder = "#1E293B";
                    break;
                case "green":
                    cMid = "#ECFDF5";
                    cAccent = "#6EE7B7";
                    cAccentLight = "#A7F3D0";
                    cBorder = "#1E293B";
                    break;
                case "orange":
                    cMid = "#FFF7ED";
                    cAccent = "#FB923C";
                    cAccentLight = "#FED7AA";
                    cBorder = "#1E293B";
                    break;
                default:
                    cMid = "#E8F4FD";
                    cAccent = "#7BA7D8";
                    cAccentLight = "#B8D4F1";
                    cBorder = "#1E293B";
                    break;
            }
        }

        WebSettings ws = splashWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        splashWebView.setBackgroundColor(Color.TRANSPARENT);

        String html = buildSvgHtml(cMid, cAccent, cAccentLight, cBorder);
        splashWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private String buildSvgHtml(String cMid, String cAccent, String cAccentLight, String cBorder) {
        return "<!DOCTYPE html><html><head>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>"
                + "*{margin:0;padding:0;box-sizing:border-box;}"
                // full layar, tanpa scroll
                + "html,body{width:100%;height:100%;overflow:hidden;background:transparent;"
                + "display:flex;align-items:center;justify-content:center;}"
                + "svg{display:block;width:100%;height:100%;}"

                // ── speech bubble ──
                + ".bubble{animation:pulse 2.2s ease-in-out infinite;transform-origin:200px 90px;}"

                // ── dots ──
                + ".d1{animation:blink 1.2s 0.0s infinite;}"
                + ".d2{animation:blink 1.2s 0.4s infinite;}"
                + ".d3{animation:blink 1.2s 0.8s infinite;}"

                // ── sparkle ──
                + ".sp{animation:spark 2s ease-in-out infinite;}"
                + ".sp.a1{animation-delay:0.0s;}.sp.a2{animation-delay:0.5s;}"
                + ".sp.a3{animation-delay:1.0s;}.sp.a4{animation-delay:1.5s;}"
                + ".sp.a5{animation-delay:0.3s;}.sp.a6{animation-delay:0.9s;}"
                + ".sp.a7{animation-delay:1.3s;}.sp.a8{animation-delay:0.7s;}"
                + ".sp.a9{animation-delay:0.2s;}.sp.a10{animation-delay:1.1s;}"

                // ── bar ──
                + ".bar{animation:barload 2.4s ease-in-out infinite;}"

                // ── environment & karakter ──
                + ".sway{animation:sway 3.6s ease-in-out infinite;}"
                + ".fox{animation:jump 1.6s ease-in-out infinite;transform-origin:56px 330px;}"
                + ".fish{animation:swim 2.2s ease-in-out infinite;}"
                + ".fish2{animation:swim2 2.8s ease-in-out infinite;}"
                + ".cathead{transform-origin:192px 294px;}"
                + ".ghost{animation:floatup 2s ease-in-out infinite;transform-origin:340px 300px;}"
                + ".cat{animation:jump2 1.8s ease-in-out infinite;transform-origin:470px 330px;}"
                + ".mushroom{animation:bounce 1.4s ease-in-out infinite;transform-origin:130px 330px;}"

                // ── bubble pop ──
                + ".bp1{animation:pop 2.4s 0.0s infinite;}"
                + ".bp2{animation:pop 2.4s 0.8s infinite;}"
                + ".bp3{animation:pop 2.4s 1.6s infinite;}"
                + ".bp4{animation:pop 2.4s 0.4s infinite;}"
                + ".bp5{animation:pop 2.4s 1.2s infinite;}"
                + ".bp6{animation:pop 2.4s 2.0s infinite;}"
                + ".bp7{animation:pop 2.4s 0.6s infinite;}"
                + ".bp8{animation:pop 2.4s 1.8s infinite;}"
                + ".bxa{animation:bxa 2.4s ease-out infinite;}"
                + ".bxb{animation:bxb 2.4s ease-out infinite;}"
                + ".bxc{animation:bxc 2.4s ease-out infinite;}"
                + ".bxd{animation:bxd 2.4s ease-out infinite;}"
                + ".bxa.d1{animation-delay:0.0s;}.bxb.d1{animation-delay:0.0s;}"
                + ".bxc.d1{animation-delay:0.0s;}.bxd.d1{animation-delay:0.0s;}"
                + ".bxa.d2{animation-delay:0.8s;}.bxb.d2{animation-delay:0.8s;}"
                + ".bxc.d2{animation-delay:0.8s;}.bxd.d2{animation-delay:0.8s;}"
                + ".bxa.d3{animation-delay:1.6s;}.bxb.d3{animation-delay:1.6s;}"
                + ".bxc.d3{animation-delay:1.6s;}.bxd.d3{animation-delay:1.6s;}"
                + ".bxa.d4{animation-delay:0.4s;}.bxb.d4{animation-delay:0.4s;}"
                + ".bxc.d4{animation-delay:0.4s;}.bxd.d4{animation-delay:0.4s;}"

                // ── keyframes ──
                + "@keyframes pulse{0%,100%{transform:scale(1);}50%{transform:scale(1.04);}}"
                + "@keyframes blink{0%,80%,100%{opacity:0.15;}40%{opacity:1;}}"
                + "@keyframes spark{0%,100%{opacity:0;transform:scale(0.3);}50%{opacity:1;transform:scale(1);}}"
                + "@keyframes barload{0%{width:0;}65%{width:160px;}80%{width:160px;}100%{width:0;}}"
                + "@keyframes sway{0%,100%{transform:skewX(0deg);}50%{transform:skewX(-6deg);}}"
                + "@keyframes jump{0%,100%{transform:translateY(0);}50%{transform:translateY(-10px);}}"
                + "@keyframes jump2{0%,100%{transform:translateY(0);}50%{transform:translateY(-8px);}}"
                + "@keyframes swim{0%,100%{transform:translateX(0);}50%{transform:translateX(12px);}}"
                + "@keyframes swim2{0%,100%{transform:translateX(0);}50%{transform:translateX(-10px);}}"
                + "@keyframes floatup{0%,100%{transform:translateY(0);}50%{transform:translateY(-8px);}}"
                + "@keyframes bounce{0%,100%{transform:scaleY(1);}50%{transform:scaleY(0.88);}}"
                + "@keyframes pop{"
                +   "0%{opacity:0;transform:scale(0.1) translateY(6px);}"
                +   "30%{opacity:1;transform:scale(1) translateY(-6px);}"
                +   "65%{opacity:0.8;transform:scale(1.2) translateY(-12px);}"
                +   "80%{opacity:0;transform:scale(0.1) translateY(-16px);}"
                +   "100%{opacity:0;}"
                + "}"
                + "@keyframes bxa{0%,65%{opacity:0;transform:translate(0,0);}80%{opacity:1;transform:translate(-8px,-6px);}100%{opacity:0;transform:translate(-14px,-12px);}}"
                + "@keyframes bxb{0%,65%{opacity:0;transform:translate(0,0);}80%{opacity:1;transform:translate(8px,-6px);}100%{opacity:0;transform:translate(14px,-12px);}}"
                + "@keyframes bxc{0%,65%{opacity:0;transform:translate(0,0);}80%{opacity:1;transform:translate(-6px,4px);}100%{opacity:0;transform:translate(-10px,10px);}}"
                + "@keyframes bxd{0%,65%{opacity:0;transform:translate(0,0);}80%{opacity:1;transform:translate(6px,4px);}100%{opacity:0;transform:translate(10px,10px);}}"

                + "</style></head><body>"
                // viewBox 540×420 — proporsi layar portrait, scale ke full
                + "<svg viewBox='0 0 540 420' preserveAspectRatio='xMidYMid meet' xmlns='http://www.w3.org/2000/svg'>"

                // ══════════════════════════════════════════════════
                // SPEECH BUBBLE — Tepat di tengah atas
                // ══════════════════════════════════════════════════
                + "<g transform='translate(45, 10)'>"
                + "<g class='bubble'>"
                // border luar piksel
                + "<rect x='100' y='20' width='10' height='10' fill='" + cBorder + "'/>"
                + "<rect x='110' y='12' width='10' height='10' fill='" + cBorder + "'/>"
                + "<rect x='120' y='10' width='120' height='8'  fill='" + cBorder + "'/>"
                + "<rect x='240' y='8'  width='10' height='10' fill='" + cBorder + "'/>"
                + "<rect x='250' y='8'  width='10' height='8'  fill='" + cBorder + "'/>"
                + "<rect x='260' y='10' width='80' height='8'  fill='" + cBorder + "'/>"
                + "<rect x='340' y='8'  width='8'  height='8'  fill='" + cBorder + "'/>"
                + "<rect x='348' y='10' width='8'  height='8'  fill='" + cBorder + "'/>"
                + "<rect x='356' y='14' width='8'  height='106' fill='" + cBorder + "'/>"
                + "<rect x='348' y='120' width='8' height='10' fill='" + cBorder + "'/>"
                + "<rect x='338' y='124' width='10' height='10' fill='" + cBorder + "'/>"
                + "<rect x='112' y='130' width='226' height='8' fill='" + cBorder + "'/>"
                + "<rect x='102' y='124' width='10' height='8'  fill='" + cBorder + "'/>"
                + "<rect x='94'  y='114' width='8'  height='10' fill='" + cBorder + "'/>"
                + "<rect x='88'  y='32' width='8'  height='82' fill='" + cBorder + "'/>"
                + "<rect x='94'  y='22' width='8'  height='10' fill='" + cBorder + "'/>"
                // fill luar
                + "<rect x='102' y='26' width='246' height='100' fill='" + cAccentLight + "'/>"
                + "<rect x='94'  y='34' width='8'   height='88'  fill='" + cAccentLight + "'/>"
                + "<rect x='346' y='22' width='8'   height='104' fill='" + cAccentLight + "'/>"
                + "<rect x='110' y='124' width='228' height='8'  fill='" + cAccentLight + "'/>"
                // ring dalam
                + "<rect x='114' y='34' width='224' height='82' fill='" + cAccent + "'/>"
                + "<rect x='104' y='44' width='10'  height='64' fill='" + cAccent + "'/>"
                + "<rect x='334' y='44' width='10'  height='64' fill='" + cAccent + "'/>"
                + "<rect x='114' y='114' width='224' height='8' fill='" + cAccent + "'/>"
                // notch kanan
                + "<rect x='296' y='90' width='38' height='24' fill='" + cAccentLight + "'/>"
                // center
                + "<rect x='124' y='44' width='204' height='64' fill='" + cMid + "'/>"
                + "<rect x='114' y='54' width='10'  height='46' fill='" + cMid + "'/>"
                + "<rect x='324' y='54' width='10'  height='36' fill='" + cMid + "'/>"
                + "<rect x='124' y='106' width='172' height='8' fill='" + cMid + "'/>"
                + "<rect x='292' y='86' width='30' height='20' fill='" + cAccentLight + "'/>"
                // 3 dots besar
                + "<rect class='d1' x='176' y='68' width='16' height='16' fill='" + cAccent + "'/>"
                + "<rect class='d2' x='204' y='68' width='16' height='16' fill='" + cAccent + "'/>"
                + "<rect class='d3' x='232' y='68' width='16' height='16' fill='" + cAccent + "'/>"
                + "</g></g>"

                // ══════════════════════════════════════════════════
                // SPARKLE ATAS
                // ══════════════════════════════════════════════════
                + "<g transform='translate(45, 10)'>"
                + "<rect class='sp a1'  x='70'  y='18'  width='8' height='8' fill='" + cBorder + "'/>"
                + "<rect class='sp a2'  x='78'  y='8'   width='6' height='6' fill='" + cBorder + "'/>"
                + "<rect class='sp a3'  x='368' y='6'   width='8' height='8' fill='" + cBorder + "'/>"
                + "<rect class='sp a4'  x='380' y='22'  width='6' height='6' fill='" + cBorder + "'/>"
                + "<rect class='sp a5'  x='60'  y='100' width='6' height='6' fill='" + cBorder + "'/>"
                + "<rect class='sp a6'  x='382' y='112' width='8' height='8' fill='" + cBorder + "'/>"
                + "<rect class='sp a7'  x='150' y='148' width='6' height='6' fill='" + cBorder + "'/>"
                + "<rect class='sp a8'  x='270' y='148' width='6' height='6' fill='" + cBorder + "'/>"
                + "<rect class='sp a9'  x='400' y='60'  width='6' height='6' fill='" + cBorder + "'/>"
                + "<rect class='sp a10' x='50'  y='54'  width='6' height='6' fill='" + cBorder + "'/>"
                + "</g>"

                // ══════════════════════════════════════════════════
                // LOADING BAR
                // ══════════════════════════════════════════════════
                + "<rect x='188' y='165' width='164' height='12' fill='" + cMid + "'/>"
                + "<rect x='188' y='165' width='164' height='12' fill='none' stroke='" + cBorder + "' stroke-width='2'/>"
                + "<rect x='185' y='162' width='5' height='5' fill='" + cBorder + "'/>"
                + "<rect x='350' y='162' width='5' height='5' fill='" + cBorder + "'/>"
                + "<rect x='185' y='175' width='5' height='5' fill='" + cBorder + "'/>"
                + "<rect x='350' y='175' width='5' height='5' fill='" + cBorder + "'/>"
                + "<clipPath id='bc'><rect x='190' y='167' width='160' height='8'/></clipPath>"
                + "<g clip-path='url(#bc)'>"
                + "<rect class='bar' x='190' y='167' width='0' height='8' fill='" + cAccent + "'/>"
                + "</g>"
                + "<text x='270' y='192' text-anchor='middle' font-family='monospace' font-size='11' fill='" + cBorder + "' font-weight='bold' letter-spacing='3' opacity='0.6'>LOADING...</text>"

                // ══════════════════════════════════════════════════
                // PIJAKAN / GROUND SCENERY (Rumput, Batu, Terumbu)
                // Diturunkan sedikit menggunakan translate(0, 25)
                // ══════════════════════════════════════════════════
                + "<g class='scenery' transform='translate(0, 25)'>"
                // Garis tanah utama
                + "<rect x='0' y='465' width='540' height='2' fill='" + cBorder + "'/>"
                + "<rect x='0' y='467' width='540' height='14' fill='" + cMid + "' opacity='0.3'/>"

                // Tekstur tanah (dots pelengkap)
                + "<rect x='30'  y='470' width='6' height='2' fill='" + cBorder + "' opacity='0.2'/>"
                + "<rect x='120' y='474' width='4' height='2' fill='" + cBorder + "' opacity='0.2'/>"
                + "<rect x='250' y='469' width='8' height='2' fill='" + cBorder + "' opacity='0.2'/>"
                + "<rect x='380' y='473' width='4' height='2' fill='" + cBorder + "' opacity='0.2'/>"
                + "<rect x='480' y='470' width='6' height='2' fill='" + cBorder + "' opacity='0.2'/>"

                // Rumput Kiri (Swaying)
                + "<g class='sway' style='transform-origin:15px 465px;'>"
                + "<rect x='14' y='452' width='4' height='13' fill='#6B8E7B'/>"
                + "<rect x='10' y='456' width='4' height='9' fill='#6B8E7B'/>"
                + "<rect x='18' y='460' width='4' height='5' fill='#8BA888'/>"
                + "</g>"

                // Terumbu Karang Biru
                + "<rect x='120' y='440' width='8' height='25' fill='#4D97A8'/>"
                + "<rect x='128' y='446' width='6' height='6' fill='#4D97A8'/>"
                + "<rect x='114' y='452' width='6' height='6' fill='#4D97A8'/>"
                + "<rect x='116' y='444' width='4' height='6' fill='#74B4C2'/>"

                // Batu Tengah
                + "<rect x='210' y='456' width='16' height='9' fill='#94A3B8'/>"
                + "<rect x='214' y='452' width='8' height='4' fill='#CBD5E1'/>"

                // Rumput Laut (Swaying)
                + "<g class='sway' style='transform-origin:290px 465px;'>"
                + "<rect x='288' y='435' width='4' height='30' fill='#6B8E7B'/>"
                + "<rect x='292' y='442' width='4' height='10' fill='#6B8E7B'/>"
                + "<rect x='284' y='452' width='4' height='10' fill='#8BA888'/>"
                + "</g>"

                // Terumbu Karang Pink
                + "<rect x='380' y='430' width='6' height='35' fill='#C17798'/>"
                + "<rect x='386' y='440' width='8' height='6' fill='#C17798'/>"
                + "<rect x='374' y='452' width='6' height='6' fill='#C17798'/>"
                + "<rect x='382' y='434' width='2' height='12' fill='#D19AAF'/>"

                // Batu & Rumput Kanan
                + "<rect x='490' y='460' width='12' height='5' fill='#94A3B8'/>"
                + "<g class='sway' style='transform-origin:498px 465px;'>"
                + "<rect x='496' y='452' width='4' height='8' fill='#8BA888'/>"
                + "</g>"
                + "</g>"

                // ══════════════════════════════════════════════════
                // KARAKTER 2: JAMUR
                // ══════════════════════════════════════════════════
                + "<g transform='translate(53, 160)'>"
                + "<g class='mushroom'>"
                + "<rect x='108' y='282' width='44' height='6'  fill='#C76262'/>"
                + "<rect x='104' y='288' width='52' height='30' fill='#C76262'/>"
                + "<rect x='100' y='292' width='6'  height='22' fill='#A14B4B'/>"
                + "<rect x='154' y='292' width='6'  height='22' fill='#A14B4B'/>"
                + "<rect x='112' y='292' width='8' height='8' fill='#CBD5E1'/>"
                + "<rect x='140' y='296' width='6' height='6' fill='#CBD5E1'/>"
                + "<rect x='126' y='300' width='6' height='6' fill='#CBD5E1'/>"
                + "<rect x='112' y='318' width='36' height='14' fill='#94A3B8'/>"
                + "<rect x='108' y='320' width='4'  height='10' fill='#64748B'/>"
                + "<rect x='148' y='320' width='4'  height='10' fill='#64748B'/>"
                + "<rect x='118' y='294' width='4' height='4' fill='" + cBorder + "'/>"
                + "<rect x='119' y='295' width='2' height='2' fill='" + cMid + "'/>"
                + "<rect x='138' y='294' width='4' height='4' fill='" + cBorder + "'/>"
                + "<rect x='139' y='295' width='2' height='2' fill='" + cMid + "'/>"
                + "</g></g>"

                // ══════════════════════════════════════════════════
                // KARAKTER 3: KEPALA KUCING KOTAK
                // ══════════════════════════════════════════════════
                + "<g transform='translate(78, 188)'>"
                + "<rect class='sp a5' x='174' y='274' width='4' height='4' fill='" + cAccent + "'/>"
                + "<rect class='sp a8' x='208' y='278' width='3' height='3' fill='" + cAccentLight + "'/>"
                + "<rect class='sp a9' x='170' y='300' width='3' height='3' fill='" + cAccentLight + "'/>"
                + "<rect class='sp a6' x='206' y='304' width='4' height='4' fill='" + cAccent + "'/>"
                + "<g class='cathead'>"
                + "<rect x='182' y='286' width='20' height='16' fill='#DEC273'/>"
                + "<rect x='182' y='280' width='6'  height='6'  fill='#CBA344'/>"
                + "<rect x='196' y='280' width='6'  height='6'  fill='#CBA344'/>"
                + "<rect x='186' y='292' width='2'  height='2'  fill='" + cBorder + "'/>"
                + "<rect x='196' y='292' width='2'  height='2'  fill='" + cBorder + "'/>"
                + "<rect x='190' y='294' width='4'  height='2'  fill='#A8832E'/>"
                + "</g></g>"

                // ══════════════════════════════════════════════════
                // IKAN 1
                // ══════════════════════════════════════════════════
                + "<g transform='translate(-40, 130)'>"
                + "<g class='fish'>"
                + "<rect x='168' y='244' width='10' height='6' fill='#4D97A8'/>"
                + "<rect x='166' y='242' width='5'  height='3' fill='#4D97A8'/>"
                + "<rect x='166' y='248' width='5'  height='3' fill='#4D97A8'/>"
                + "<rect x='174' y='238' width='32' height='18' fill='#4D97A8'/>"
                + "<rect x='176' y='242' width='24' height='8'  fill='#74B4C2'/>"
                + "<rect x='182' y='234' width='10' height='6'  fill='#377382'/>"
                + "<rect x='196' y='240' width='6'  height='6'  fill='" + cBorder + "'/>"
                + "<rect x='197' y='241' width='3'  height='3'  fill='" + cMid + "'/>"
                + "<rect x='202' y='246' width='3'  height='3'  fill='" + cBorder + "'/>"
                + "<rect x='178' y='239' width='3'  height='3'  fill='#377382'/>"
                + "<rect x='186' y='239' width='3'  height='3'  fill='#377382'/>"
                + "</g></g>"

                // ══════════════════════════════════════════════════
                // IKAN 2
                // ══════════════════════════════════════════════════
                + "<g transform='translate(40, 110)'>"
                + "<g class='fish2'>"
                + "<rect x='366' y='252' width='10' height='6' fill='#C17798'/>"
                + "<rect x='374' y='250' width='5'  height='3' fill='#C17798'/>"
                + "<rect x='374' y='256' width='5'  height='3' fill='#C17798'/>"
                + "<rect x='338' y='246' width='32' height='18' fill='#C17798'/>"
                + "<rect x='340' y='250' width='24' height='8'  fill='#D19AAF'/>"
                + "<rect x='346' y='242' width='10' height='6'  fill='#9E5877'/>"
                + "<rect x='342' y='248' width='6'  height='6'  fill='" + cBorder + "'/>"
                + "<rect x='343' y='249' width='3'  height='3'  fill='" + cMid + "'/>"
                + "<rect x='338' y='254' width='3'  height='3'  fill='" + cBorder + "'/>"
                + "<rect x='356' y='247' width='3'  height='3'  fill='#9E5877'/>"
                + "<rect x='362' y='247' width='3'  height='3'  fill='#9E5877'/>"
                + "</g></g>"

                // ══════════════════════════════════════════════════
                // GELEMBUNG POP 8BIT
                // ══════════════════════════════════════════════════
                + "<g transform='translate(47, 160)'>" // Disesuaikan dengan posisi Rubah
                + "<g class='bp1'><rect x='40' y='258' width='6' height='6' fill='none' stroke='" + cAccent + "' stroke-width='2'/></g>"
                + "<g class='bxa d1'><rect x='40' y='258' width='3' height='3' fill='" + cAccent + "'/></g>"
                + "<g class='bxb d1'><rect x='44' y='258' width='3' height='3' fill='" + cAccentLight + "'/></g>"
                + "<g class='bxc d1'><rect x='40' y='262' width='3' height='3' fill='" + cMid + "'/></g>"
                + "<g class='bxd d1'><rect x='44' y='262' width='3' height='3' fill='" + cAccent + "'/></g>"
                + "</g>"

                + "<g transform='translate(53, 160)'>"
                + "<g class='bp2'><rect x='122' y='266' width='8' height='8' fill='none' stroke='" + cAccentLight + "' stroke-width='2'/></g>"
                + "<g class='bxa d2'><rect x='122' y='266' width='3' height='3' fill='" + cAccentLight + "'/></g>"
                + "<g class='bxb d2'><rect x='127' y='266' width='3' height='3' fill='" + cAccent + "'/></g>"
                + "<g class='bxc d2'><rect x='122' y='271' width='3' height='3' fill='" + cBorder + "'/></g>"
                + "<g class='bxd d2'><rect x='127' y='271' width='3' height='3' fill='" + cAccentLight + "'/></g>"
                + "</g>"

                + "<g transform='translate(31, 140)'>" // Disesuaikan dengan posisi Hantu
                + "<g class='bp3'><rect x='326' y='254' width='6' height='6' fill='none' stroke='" + cAccent + "' stroke-width='2'/></g>"
                + "<g class='bxa d3'><rect x='326' y='254' width='3' height='3' fill='" + cAccent + "'/></g>"
                + "<g class='bxb d3'><rect x='330' y='254' width='3' height='3' fill='" + cMid + "'/></g>"
                + "<g class='bxc d3'><rect x='326' y='258' width='3' height='3' fill='" + cAccentLight + "'/></g>"
                + "<g class='bxd d3'><rect x='330' y='258' width='3' height='3' fill='" + cAccent + "'/></g>"
                + "</g>"

                + "<g transform='translate(-7, 158)'>" // Disesuaikan dengan posisi Kucing 8-bit
                + "<g class='bp4'><rect x='454' y='258' width='8' height='8' fill='none' stroke='" + cAccentLight + "' stroke-width='2'/></g>"
                + "<g class='bxa d4'><rect x='454' y='258' width='3' height='3' fill='" + cAccentLight + "'/></g>"
                + "<g class='bxb d4'><rect x='459' y='258' width='3' height='3' fill='" + cAccent + "'/></g>"
                + "<g class='bxc d4'><rect x='454' y='263' width='3' height='3' fill='" + cMid + "'/></g>"
                + "<g class='bxd d4'><rect x='459' y='263' width='3' height='3' fill='" + cBorder + "'/></g>"
                + "</g>"

                // bubble ekstra di background
                + "<g class='bp5'><rect x='80'  y='342' width='5' height='5' fill='none' stroke='" + cAccent + "' stroke-width='1.5'/></g>"
                + "<g class='bp6'><rect x='248' y='334' width='5' height='5' fill='none' stroke='" + cAccentLight + "' stroke-width='1.5'/></g>"
                + "<g class='bp7'><rect x='406' y='348' width='4' height='4' fill='none' stroke='" + cAccent + "' stroke-width='1'/></g>"
                + "<g class='bp8'><rect x='160' y='330' width='4' height='4' fill='none' stroke='" + cMid + "' stroke-width='1'/></g>"

                + "</svg></body></html>";
    }


    // ─── Navigation ──────────────────────────────────────────────────────────

    private void goNext() {
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        startActivity(new Intent(this, UserSession.getInstance().isLoggedIn() ? MainActivity.class : LoginActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }


    // ─── Animations ──────────────────────────────────────────────────────────

    private void playSplashSequence() {
        handler.postDelayed(this::checkLocationPermission, 3000);
    }

    private void preloadFlagAndGreeting(String countryCode) {
        isFlagLoaded = false;
        isGreetingReady = false;

        String flagUrl = "https://flagcdn.com/w320/" + countryCode + ".png";
        String greeting = getManualGreeting(countryCode);

        tvGreeting.setText(greeting);
        isGreetingReady = true;

        int size = 200;
        RequestOptions options = new RequestOptions()
                .override(size, (int) (size * 0.665f))
                .transform(new RoundedCorners(12));

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
                        Log.e(TAG, "Flag load failed", e);
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
        if (isFlagLoaded && isGreetingReady) {
            handler.post(this::transitionToPhase2);
        }
    }

    private void transitionToPhase2() {
        splashWebView.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction(() -> {
                    splashWebView.setVisibility(View.GONE);
                    showGreetingSequence();
                })
                .start();
    }

    private void showGreetingSequence() {
        greetingContainer.setVisibility(View.VISIBLE);
        greetingContainer.setAlpha(0f);

        imgFlag.setVisibility(View.VISIBLE);
        imgFlag.setAlpha(0f);
        imgFlag.setScaleX(0.8f);
        imgFlag.setScaleY(0.8f);

        imgFlag.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(600)
                .withStartAction(() -> greetingContainer.animate().alpha(1f).setDuration(600).start())
                .withEndAction(this::showGreetingText)
                .start();
    }

    private void showGreetingText() {
        tvGreeting.setVisibility(View.VISIBLE);
        tvGreeting.setAlpha(0f);
        tvGreeting.setTranslationY(20f);

        tvGreeting.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(500)
                .setStartDelay(200)
                .withEndAction(() -> handler.postDelayed(this::goNext, 2000))
                .start();
    }


    // ─── Location Permissions ────────────────────────────────────────────────

    private void checkLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_REQUEST
            );
        } else {
            checkLocationSettings();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkLocationSettings();
        } else {
            getNetworkLocationFallback();
        }
    }


    // ─── Location Settings & Updates ─────────────────────────────────────────

    private void checkLocationSettings() {
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000)
                .setMinUpdateIntervalMillis(2000)
                .setWaitForAccurateLocation(true)
                .build();

        LocationSettingsRequest settingsRequest = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true)
                .build();

        SettingsClient client = LocationServices.getSettingsClient(this);
        client.checkLocationSettings(settingsRequest)
                .addOnSuccessListener(this, response -> startRealtimeLocationUpdates())
                .addOnFailureListener(this, e -> {
                    if (e instanceof ResolvableApiException) {
                        try {
                            ((ResolvableApiException) e).startResolutionForResult(this, REQUEST_CHECK_SETTINGS);
                        } catch (Exception ex) {
                            Log.e(TAG, "startResolution failed", ex);
                            getNetworkLocationFallback();
                        }
                    } else {
                        getNetworkLocationFallback();
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CHECK_SETTINGS) {
            if (resultCode == RESULT_OK) {
                startRealtimeLocationUpdates();
            } else {
                getNetworkLocationFallback();
            }
        }
    }

    @SuppressLint("MissingPermission")
    private void startRealtimeLocationUpdates() {
        if (fusedLocationClient == null) {
            return;
        }

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult result) {
                Location location = result.getLastLocation();
                if (location == null) {
                    return;
                }
                handleLocation(location);
            }
        };

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, getMainLooper());
    }


    // ─── Geocoding & Processing ──────────────────────────────────────────────

    private void handleLocation(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();

        new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(SplashActivity.this, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);

                runOnUiThread(() -> {
                    if (addresses != null && !addresses.isEmpty()) {
                        String countryName = addresses.get(0).getCountryName();
                        UserSession.getInstance().setDetectedCountryName(
                                countryName != null ? countryName : ""
                        );

                        updateCountry(addresses.get(0).getCountryCode());
                    } else {
                        getNetworkLocationFallback();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Geocoder error: " + e.getMessage());
                runOnUiThread(this::getNetworkLocationFallback);
            }
        }).start();
    }

    private void updateCountry(String countryCode) {
        if (countryCode == null) {
            return;
        }

        countryCode = countryCode.toLowerCase(Locale.ROOT);

        if (!countryCode.equals(lastCountry)) {
            lastCountry = countryCode;
            preloadFlagAndGreeting(countryCode);
        }
    }


    // ─── Fallback Location (IP/Locale) ───────────────────────────────────────

    private void getNetworkLocationFallback() {
        new Thread(() -> {
            try {
                String countryCode = getCountryNameFromIP();
                runOnUiThread(() -> {
                    if (countryCode.isEmpty()) {
                        updateCountry(Locale.getDefault().getCountry());
                    } else {
                        updateCountry(countryCode);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Network location failed: " + e.getMessage());
                runOnUiThread(this::useLocaleFallback);
            }
        }).start();
    }

    private void useLocaleFallback() {
        updateCountry(Locale.getDefault().getCountry().toLowerCase());
    }

    private String getCountryNameFromIP() throws Exception {
        HttpURLConnection connection =
                (HttpURLConnection) new URL("https://ipapi.co/json/").openConnection();

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(4000);
        connection.setReadTimeout(4000);

        JSONObject json = new JSONObject(readResponse(connection));

        // simpan nama negara
        UserSession.getInstance().setDetectedCountryName(
                json.optString("country_name", "")
        );

        // return kode negara
        return json.optString("country_code", "")
                .toLowerCase(Locale.ROOT);
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
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