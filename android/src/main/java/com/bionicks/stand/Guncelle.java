package com.bionicks.stand;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

// Uygulamanın kendini güncellemesi. Sayfa sorar (günde bir, geliştirme modunda her dakika):
// GitHub'daki surum.txt bizim sürümden büyükse yeni dosya uygulamanın gizli klasörüne iner;
// kimse izlemiyorken sayfa "kur" der. Android izin verirse sessiz kurulur, vermezse "Güncelle" bildirimi çıkar.
public class Guncelle extends BroadcastReceiver {
    private static final String ADRES = "https://github.com/bionicks-dev/Stand/releases/download/apk/";
    private static volatile boolean bakiyor = false;
    static volatile int hazirSurum = 0;

    static int benimSurum(Context c) {
        try { return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode(); }
        catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    private static File dosya(Context c) { return new File(c.getCacheDir(), "guncel.apk"); }

    static void bak(final Context c) {
        if (bakiyor || hazirSurum > 0) return;
        bakiyor = true;
        new Thread(() -> {
            try {
                String t = indirMetin(ADRES + "surum.txt").trim();
                int yeni = Integer.parseInt(t);
                int ben = benimSurum(c);
                if (yeni <= ben) return;
                Kayit.yaz("Yeni uygulama sürümü var (" + yeni + ", bu " + ben + "), indiriliyor.");
                File f = dosya(c);
                indirDosya(ADRES + "StandKamera.apk", f);
                hazirSurum = yeni;
                Kayit.yaz("Yeni uygulama sürümü indirildi; kimse izlemiyorken kurulacak.");
            } catch (Exception e) {
                Kayit.yaz("Uygulama güncellemesine bakılamadı: " + e.getClass().getSimpleName());
            } finally { bakiyor = false; }
        }, "stand-guncelle").start();
    }

    static void kur(Context c) {
        if (hazirSurum == 0) return;
        if (!c.getPackageManager().canRequestPackageInstalls()) {
            Kayit.yaz("Güncelleme kurulamıyor: \"Bilinmeyen uygulamaları yükle\" izni yok (Pixel uygulaması bölümünden verin).");
            return;
        }
        final int surum = hazirSurum;
        hazirSurum = 0;
        try {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            p.setAppPackageName(c.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            int id = pi.createSession(p);
            PackageInstaller.Session s = pi.openSession(id);
            File f = dosya(c);
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("guncel", 0, f.length())) {
                byte[] b = new byte[16384]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, Guncelle.class).setAction("com.bionicks.stand.KURULUM");
            PendingIntent sonuc = PendingIntent.getBroadcast(c, 4, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            Kayit.yaz("Uygulama " + surum + " sürümüne güncelleniyor (uygulama birkaç saniye kapanıp açılacak).");
            s.commit(sonuc.getIntentSender());
            s.close();
        } catch (Exception e) {
            Kayit.yaz("Güncelleme başlatılamadı: " + e.getMessage());
        }
    }

    // Kurulum sonucu
    @Override
    public void onReceive(Context c, Intent i) {
        int durum = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (durum == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent onay = Build.VERSION.SDK_INT >= 33 ? i.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class) : eskiAl(i);
            if (onay == null) return;
            onay.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Kayit.yaz("Android güncelleme için onay istiyor: Pixel'deki \"Stand Kamera güncellemesi\" bildirimine dokunun.");
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("guncelle", "Uygulama güncellemesi", NotificationManager.IMPORTANCE_HIGH));
            PendingIntent ac = PendingIntent.getActivity(c, 5, onay, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(c, "guncelle")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Stand Kamera güncellemesi")
                    .setContentText("Kurmak için dokunun.")
                    .setContentIntent(ac).setAutoCancel(true).build();
            nm.notify(2, n);
        } else if (durum != PackageInstaller.STATUS_SUCCESS) {
            Kayit.yaz("Güncelleme kurulamadı: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent eskiAl(Intent i) { return i.getParcelableExtra(Intent.EXTRA_INTENT); }

    private static HttpURLConnection ac(String adres) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(adres).openConnection();
        h.setInstanceFollowRedirects(true);
        h.setConnectTimeout(15000); h.setReadTimeout(30000);
        h.setUseCaches(false);
        int kod = h.getResponseCode();
        if (kod != 200) throw new Exception("HTTP " + kod);
        return h;
    }

    private static String indirMetin(String adres) throws Exception {
        HttpURLConnection h = ac(adres);
        try (InputStream in = h.getInputStream()) {
            byte[] b = new byte[64]; int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8") : "";
        } finally { h.disconnect(); }
    }

    private static void indirDosya(String adres, File f) throws Exception {
        HttpURLConnection h = ac(adres);
        try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        } finally { h.disconnect(); }
    }
}
