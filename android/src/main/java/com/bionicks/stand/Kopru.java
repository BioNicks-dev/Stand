package com.bionicks.stand;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;

// Sayfanın gördüğü "StandApp": ekranı aç, ekranı açık tut, nabız, kayıt, izinler, sunucu bilgisi, durdur.
final class Kopru {
    private final Context c;
    private final Handler h = new Handler(Looper.getMainLooper());
    Kopru(Context c) { this.c = c; }

    @JavascriptInterface public String surum() { return String.valueOf(Guncelle.benimSurum(c)); }

    @JavascriptInterface public void uyan() { h.post(() -> Bekci.uyandir(c, null)); }

    // Kamera açıkken ekran açık kalsın; kapanınca Pixel kendi süresiyle (Screen timeout) söner
    @JavascriptInterface public void ekran(final boolean acik) {
        h.post(() -> {
            Ana a = Ana.ben;
            if (a == null) return;
            if (acik) a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @JavascriptInterface public void nabiz() { Bekci.sonNabiz = SystemClock.elapsedRealtime(); }

    @JavascriptInterface public String kayitAl() { return Kayit.al(); }

    // Bekçinin duyduğu, sayfanın kaçırmış olabileceği izleme isteği (bir kez verilir)
    @JavascriptInterface public String bekleyenIstek() { return Bekci.bekleyenAl(); }

    // Sayfa sunucu bilgisini bekçiye verir (bekçi isteği kendisi dinlesin)
    @JavascriptInterface public void baglanti(String kod, String ss) { Bekci.ayarla(c, kod, ss); }

    @JavascriptInterface public String bilgi() {
        JSONObject o = new JSONObject();
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            o.put("surum", Guncelle.benimSurum(c));
            o.put("yukle", c.getPackageManager().canRequestPackageInstalls());
            o.put("ustte", Settings.canDrawOverlays(c));
            o.put("pil", pm.isIgnoringBatteryOptimizations(c.getPackageName()));
            o.put("kamera", Sayfa.izinVar(c));
            o.put("bekci", Bekci.bagli);
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            o.put("bildirim", nm.isNotificationListenerAccessGranted(new ComponentName(c, Bildirim.class)));
            o.put("erisim", Erisim.izinVar(c));
            o.put("rehber", Santral.rehberIzni(c));   // uygulama 14: santral (WhatsApp'tan arama)   // uygulama 11: WhatsApp aramasını ekrandan kabul
        } catch (Exception e) { }
        return o.toString();
    }

    // Telefonun ilgili ayar sayfasını açar: "ustte", "pil", "uygulama"
    @JavascriptInterface public void ayarAc(final String ne) {
        h.post(() -> {
            Uri p = Uri.parse("package:" + c.getPackageName());
            Intent a;
            if ("ustte".equals(ne)) a = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, p);
            else if ("yukle".equals(ne)) a = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, p);
            else if ("pil".equals(ne)) a = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, p);
            else if ("bildirim".equals(ne)) a = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(c, Bildirim.class).flattenToString());
            else if ("erisim".equals(ne)) a = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, new ComponentName(c, Erisim.class).flattenToString());
            else a = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, p);
            a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if ("erisim".equals(ne)) {
                // Stand Kamera'nın kendi sayfası açılmazsa genel Erişilebilirlik listesi
                try { c.startActivity(a); return; } catch (Exception e) { }
                a = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            try { c.startActivity(a); }
            catch (Exception e) {
                try { c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, p).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception e2) { }
            }
        });
    }

    // Kendini güncelleme: sayfa bakmasını söyler; indirildiyse ve kimse izlemiyorsa kurdurur
    @JavascriptInterface public void guncellemeBak() { Guncelle.bak(c); }
    @JavascriptInterface public boolean guncellemeHazir() { return Guncelle.hazirSurum > 0; }
    @JavascriptInterface public void guncellemeKur() { h.post(() -> Guncelle.kur(c)); }
    // Uygulama 13: güncelleme ekranı
    @JavascriptInterface public int guncellemeNo() { return Guncelle.hazirSurum; }
    @JavascriptInterface public String guncellemeDurum() { return Guncelle.durum; }          // "", "onay", "hata"
    @JavascriptInterface public String guncellemeSonuc() { return Guncelle.sonuc(c); }       // bir kez: yeni sürüm no
    // Uygulama 13: durum kutusu için son WhatsApp araması ("19:58 Ben Türkiye aradı, açıldı")
    @JavascriptInterface public String waSon() { return Arama.sonAl(c); }
    // Uygulama 14 (santral, parça A): deneme araması
    @JavascriptInterface public void waAra(String ad) { Santral.ara(c, ad); }
    @JavascriptInterface public String santralDurum() { return Santral.durum(); }
    // Uygulama 18: sayfa 3 sn'de bir söyler; kamera kullanılıyorsa santral WhatsApp'tan çaldırmaz
    @JavascriptInterface public void kameraMesgul(boolean m) { Santral.kameraMesgul = m; Santral.mesgulZaman = System.currentTimeMillis(); }

    // WhatsApp aramaları (uygulama 7): kamera sayfasındaki ayar
    @JavascriptInterface public String waAyar() { return Arama.ayarAl(c); }
    @JavascriptInterface public void waAyarYaz(String json) { Arama.ayarYaz(c, json); }

    @JavascriptInterface public void durdur() {
        Kayit.yaz("Uygulama elle durduruldu.");
        c.startService(new Intent(c, Bekci.class).setAction(Bekci.DURDUR));
    }
}
