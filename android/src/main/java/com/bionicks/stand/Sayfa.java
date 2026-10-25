package com.bionicks.stand;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// kamera.html'in açık olduğu sayfa. Ekrandan bağımsız yaşar: ekran (Ana) kapansa da sayfa kapanmaz,
// ekran yeniden açılınca aynı sayfa ekrana takılır.
final class Sayfa {
    static final String ADRES = "https://bionicks-dev.github.io/Stand/kamera.html";
    private static final String SITE = "bionicks-dev.github.io";
    static WebView w;
    private static MutableContextWrapper baglam;
    private static Context uyg;
    // Uygulama 17: sayfa hiç yüklenemezse (internet yoksa) 30 sn'de bir tekrar dene; siyah ekranda kalmasın
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static boolean tekrarBekliyor = false;
    private static int hataSayisi = 0;
    private static final Runnable tekrar = () -> { tekrarBekliyor = false; if (w != null) { Kayit.yaz("Sayfa yeniden yükleniyor."); yukle(); } };

    static void hazirla(Context c) {
        if (w != null) return;
        uyg = c.getApplicationContext();
        baglam = new MutableContextWrapper(uyg);
        w = new WebView(baglam);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);   // zil ve ses dokunuşsuz çalsın
        w.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        w.setBackgroundColor(0xFF000000);
        w.addJavascriptInterface(new Kopru(uyg), "StandApp");
        w.setWebChromeClient(new WebChromeClient() {
            // Uygulama 17: kamera kapalıyken görüntü kutusunda Android'in varsayılan resmi (robot) çıkmasın
            @Override public Bitmap getDefaultVideoPoster() { return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); }
            @Override public void onPermissionRequest(PermissionRequest r) {
                if (izinVar(uyg)) r.grant(r.getResources());
                else { r.deny(); Kayit.yaz("Kamera/mikrofon izni yok: Stand Kamera'yı açıp izin verin."); }
            }
        });
        w.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (SITE.equals(u.getHost())) return false;
                try { uyg.startActivity(new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e) { }
                return true;
            }
            @Override public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r == null || !r.isForMainFrame()) return;   // sadece sayfanın kendisi
                hataSayisi++;
                if (hataSayisi <= 3 || hataSayisi % 20 == 0)
                    Kayit.yaz("Sayfa yüklenemedi (" + (e == null ? "?" : e.getDescription()) + "), 30 sn sonra tekrar denenecek.");
                if (!tekrarBekliyor) { tekrarBekliyor = true; h.postDelayed(tekrar, 30000); }
            }
            @Override public void onPageFinished(WebView v, String u) {
                if (hataSayisi > 0 && !tekrarBekliyor) { Kayit.yaz("Sayfa yüklendi (" + hataSayisi + " başarısız denemeden sonra)."); hataSayisi = 0; }
            }
            @Override public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                Kayit.yaz("Sayfa çöktü, yeniden açılıyor.");
                yenidenKur();
                return true;
            }
        });
        Kayit.yaz("Sayfa açılıyor.");
        yukle();
    }

    static boolean izinVar(Context c) {
        return c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private static void yukle() {
        Bekci.sonNabiz = SystemClock.elapsedRealtime();
        w.loadUrl(ADRES + "?uygulama=1&t=" + System.currentTimeMillis());
    }

    static void yenile() { if (w != null) yukle(); }

    private static void yenidenKur() {
        WebView eski = w;
        Activity a = Ana.ben;
        if (eski != null) {
            if (eski.getParent() != null) ((ViewGroup) eski.getParent()).removeView(eski);
            try { eski.destroy(); } catch (Exception e) { }
        }
        w = null;
        hazirla(uyg);
        if (a != null) tak(a);
    }

    // Sayfayı ekrana tak / ekrandan çıkar (sayfa yaşamaya devam eder)
    static void tak(Activity a) {
        if (w == null) hazirla(a);
        if (w.getParent() != null) ((ViewGroup) w.getParent()).removeView(w);
        baglam.setBaseContext(a);
        a.setContentView(w);
    }

    static void birak(Activity a) {
        if (w == null) return;
        if (w.getParent() != null) ((ViewGroup) w.getParent()).removeView(w);
        baglam.setBaseContext(uyg);
    }

    static void kapat() {
        if (w == null) return;
        if (w.getParent() != null) ((ViewGroup) w.getParent()).removeView(w);
        try { w.destroy(); } catch (Exception e) { }
        w = null;
    }
}
