package com.bionicks.stand;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.ArrayList;
import org.json.JSONObject;

// Ana ekran: içinde kamera.html açılır. Sayfa bu uygulamayı "StandApp" adıyla görür
// (ekranı aç, ekranı açık tut, nabız, kayıt, izinler, durdur).
public class Ana extends Activity {
    static volatile Ana ben;
    private static final String ADRES = "https://bionicks-dev.github.io/Stand/kamera.html";
    private static final String SITE = "bionicks-dev.github.io";
    private WebView w;
    private PermissionRequest bekleyenIzin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ben = this;
        Kayit.durdu(this, false);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        tamEkran();
        Bekci.baslat(this);
        kur();
        izinleriIste();
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> moveTaskToBack(true));
    }

    private void kur() {
        w = new WebView(this);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // zil ve ses dokunuşsuz çalsın
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);   // ekran kapalıyken de sayfa öncelikli kalsın
        w.setBackgroundColor(0xFF000000);
        w.addJavascriptInterface(new Kopru(), "StandApp");
        w.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(final PermissionRequest r) {
                runOnUiThread(() -> sayfaIzni(r));
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (SITE.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Kayit.yaz("Sayfa çöktü, yeniden açılıyor.");
                yenidenKur();
                return true;
            }
        });
        setContentView(w);
        w.loadUrl(ADRES + "?uygulama=1&t=" + System.currentTimeMillis());
        Bekci.sonNabiz = SystemClock.elapsedRealtime();
    }

    private void yenidenKur() {
        if (w != null) { try { w.destroy(); } catch (Exception e) { } }
        kur();
    }

    void yenile() {
        runOnUiThread(() -> { if (w != null) w.loadUrl(ADRES + "?uygulama=1&t=" + System.currentTimeMillis()); });
    }

    private void tamEkran() {
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) tamEkran(); }

    // ---------- kamera / mikrofon / bildirim izinleri ----------
    private boolean var(String izin) { return checkSelfPermission(izin) == PackageManager.PERMISSION_GRANTED; }

    private void izinleriIste() {
        ArrayList<String> l = new ArrayList<>();
        if (!var(Manifest.permission.CAMERA)) l.add(Manifest.permission.CAMERA);
        if (!var(Manifest.permission.RECORD_AUDIO)) l.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && !var(Manifest.permission.POST_NOTIFICATIONS)) l.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!l.isEmpty()) requestPermissions(l.toArray(new String[0]), 7);
    }

    private void sayfaIzni(PermissionRequest r) {
        if (var(Manifest.permission.CAMERA) && var(Manifest.permission.RECORD_AUDIO)) { r.grant(r.getResources()); return; }
        bekleyenIzin = r;
        requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 8);
    }

    @Override
    public void onRequestPermissionsResult(int kod, String[] izinler, int[] sonuc) {
        super.onRequestPermissionsResult(kod, izinler, sonuc);
        boolean tamam = var(Manifest.permission.CAMERA) && var(Manifest.permission.RECORD_AUDIO);
        if (!tamam) Kayit.yaz("Kamera ya da mikrofon izni verilmedi.");
        if (bekleyenIzin != null) {
            if (tamam) bekleyenIzin.grant(bekleyenIzin.getResources()); else bekleyenIzin.deny();
            bekleyenIzin = null;
        }
    }

    // Geri tuşu sayfayı kapatmasın: uygulama arka plana geçer, sayfa çalışmaya devam eder
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { moveTaskToBack(true); }

    @Override
    protected void onDestroy() {
        if (ben == this) ben = null;
        if (w != null) { try { w.destroy(); } catch (Exception e) { } w = null; }
        super.onDestroy();
    }

    // ---------- sayfanın kullandığı köprü (window.StandApp) ----------
    private class Kopru {
        @JavascriptInterface public String surum() { return Kayit.SURUM; }

        // İzleme isteği geldi: ekranı aç, sayfayı öne getir
        @JavascriptInterface public void uyan() {
            runOnUiThread(() -> {
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    @SuppressWarnings("deprecation")
                    PowerManager.WakeLock k = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "stand:uyan");
                    k.acquire(10000);
                } catch (Exception e) { Kayit.yaz("Ekran açma (1) hatası: " + e.getMessage()); }
                try {
                    Intent a = new Intent(Ana.this, Ana.class);
                    a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(a);
                } catch (Exception e) { Kayit.yaz("Ekran açma (2) hatası: " + e.getMessage()); }
            });
        }

        // Kamera açıkken ekran açık kalsın; kapanınca Pixel kendi süresiyle (Screen timeout) söner
        @JavascriptInterface public void ekran(final boolean acik) {
            runOnUiThread(() -> {
                if (acik) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void nabiz() { Bekci.sonNabiz = SystemClock.elapsedRealtime(); }

        @JavascriptInterface public String kayitAl() { return Kayit.al(); }

        @JavascriptInterface public String bilgi() {
            JSONObject o = new JSONObject();
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                o.put("surum", Kayit.SURUM);
                o.put("ustte", Settings.canDrawOverlays(Ana.this));
                o.put("pil", pm.isIgnoringBatteryOptimizations(getPackageName()));
                o.put("kamera", var(Manifest.permission.CAMERA) && var(Manifest.permission.RECORD_AUDIO));
            } catch (Exception e) { }
            return o.toString();
        }

        // Telefonun ilgili ayar sayfasını açar: "ustte", "pil", "uygulama"
        @JavascriptInterface public void ayarAc(final String ne) {
            runOnUiThread(() -> {
                Uri p = Uri.parse("package:" + getPackageName());
                Intent a;
                if ("ustte".equals(ne)) a = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, p);
                else if ("pil".equals(ne)) a = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, p);
                else a = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, p);
                try { startActivity(a); }
                catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, p)); } catch (Exception e2) { }
                }
            });
        }

        // Uygulamayı tamamen durdur (Chrome'dan kullanmak için). Uygulama simgesine basınca yeniden başlar.
        @JavascriptInterface public void durdur() {
            Kayit.yaz("Uygulama elle durduruldu.");
            startService(new Intent(Ana.this, Bekci.class).setAction(Bekci.DURDUR));
        }
    }
}
