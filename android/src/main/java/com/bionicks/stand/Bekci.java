package com.bionicks.stand;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

// Bekçi: ekran kapalıyken de uygulamayı (ve içindeki sayfayı) canlı tutar.
// Sayfa kapanmışsa yeniden açar; sayfa 5 dk ses vermezse yeniler. Bildirimdeki "Durdur" her şeyi kapatır.
public class Bekci extends Service {
    static final String DURDUR = "com.bionicks.stand.DURDUR";
    static volatile long sonNabiz = 0;
    private static final long NABIZ_SINIR = 5 * 60 * 1000L;
    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock islemci;
    private boolean calisiyor = false;
    private long sonUyari = 0;

    static void baslat(Context c) {
        c.startForegroundService(new Intent(c, Bekci.class));
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        bildirimiGoster();
        if (i != null && DURDUR.equals(i.getAction())) {
            Kayit.durdu(this, true);
            if (Ana.ben != null) Ana.ben.finishAndRemoveTask();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!calisiyor) {
            calisiyor = true;
            // Ekran kapalıyken de işlemci uyumasın: sunucu bağlantısı kopmasın, istek anında duyulsun
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            islemci = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stand:bekci");
            islemci.acquire();
            sonNabiz = SystemClock.elapsedRealtime();
            h.postDelayed(kontrol, 30000);
        }
        if (Ana.ben == null) sayfayiAc("Sayfa açılıyor.");
        return START_STICKY;
    }

    private final Runnable kontrol = new Runnable() {
        @Override public void run() {
            if (!calisiyor) return;
            long simdi = SystemClock.elapsedRealtime();
            if (Ana.ben == null) {
                sayfayiAc("Sayfa kapalıydı, yeniden açılıyor.");
            } else if (simdi - sonNabiz > NABIZ_SINIR) {
                Kayit.yaz("Sayfa 5 dk ses vermedi, yenileniyor.");
                sonNabiz = simdi;
                Ana.ben.yenile();
            }
            h.postDelayed(this, 30000);
        }
    };

    private void sayfayiAc(String neden) {
        if (!Settings.canDrawOverlays(this)) {
            long simdi = SystemClock.elapsedRealtime();
            if (simdi - sonUyari > 10 * 60 * 1000L) {
                sonUyari = simdi;
                Kayit.yaz("Sayfa açılamıyor: \"Diğer uygulamaların üzerinde göster\" izni yok.");
            }
            return;
        }
        Kayit.yaz(neden);
        Intent a = new Intent(this, Ana.class);
        a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try { startActivity(a); } catch (Exception e) { Kayit.yaz("Sayfa açılamadı: " + e.getMessage()); }
    }

    private void bildirimiGoster() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bekci", "Stand kamera bekliyor", NotificationManager.IMPORTANCE_LOW));
        PendingIntent ac = PendingIntent.getActivity(this, 0, new Intent(this, Ana.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dur = PendingIntent.getService(this, 1, new Intent(this, Bekci.class).setAction(DURDUR),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, "bekci")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Stand kamera bekliyor")
                .setContentText("Evi izle isteği gelince ekran kendisi açılır.")
                .setOngoing(true)
                .setContentIntent(ac)
                .addAction(new Notification.Action.Builder(null, "Durdur", dur).build())
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
    }

    @Override
    public void onDestroy() {
        calisiyor = false;
        h.removeCallbacks(kontrol);
        if (islemci != null && islemci.isHeld()) islemci.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
