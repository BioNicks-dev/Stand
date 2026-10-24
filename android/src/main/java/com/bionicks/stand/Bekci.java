package com.bionicks.stand;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

// Bekçi: ekran kapalıyken de çalışır.
//  - Sayfayı (kamera.html) kendi içinde canlı tutar.
//  - İzleme isteğini sayfadan bağımsız, kendi sunucu bağlantısıyla dinler; istek gelince ekranı açar.
//  - Sayfa 5 dk ses vermezse yeniler. Bildirimdeki "Durdur" her şeyi kapatır.
//  - Uygulama 7: gizlilik ayarını, ev kamerası kapalı bilgisini, XR'ın evde olup olmadığını ve odadaki
//    sayfaları da dinler (WhatsApp aramalarında gizlilik kuralı için, Arama.java). Pixel şarjını da gönderir.
public class Bekci extends Service {
    static final String DURDUR = "com.bionicks.stand.DURDUR";
    private static final String SUNUCU = "l08d16ff.ala.us-east-1.emqxsl.com", KULLANICI = "telefon";
    private static final long NABIZ_SINIR = 5 * 60 * 1000L;

    static volatile long sonNabiz = 0;
    static volatile boolean bagli = false;
    private static volatile Bekci ben;
    private static String bekleyen = null;
    private static long bekleyenZaman = 0, sonUyandirma = 0;

    private final Handler h = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock islemci;
    private boolean calisiyor = false;
    private volatile Mqtt mq;
    private volatile String bKonu = "", iKonu = "";
    private String mqKod = "", mqSs = "";
    private boolean kopukYazildi = false;
    private BroadcastReceiver ekranAlici, sarjAlici;
    private final ExecutorService ag = Executors.newSingleThreadExecutor();   // ağ işleri ana iş parçacığında yapılmaz
    private long sonPil = 0;
    private static final long PIL_ARA = 30 * 60 * 1000L;

    static void baslat(Context c) { c.startForegroundService(new Intent(c, Bekci.class)); }

    private static SharedPreferences ayar(Context c) { return c.getApplicationContext().getSharedPreferences("stand", Context.MODE_PRIVATE); }

    // Sayfa sunucu bilgisini verir; değiştiyse bekçinin bağlantısı yeniden kurulur
    static void ayarla(Context c, String kod, String ss) {
        if (kod == null || ss == null || kod.isEmpty() || ss.isEmpty()) return;
        SharedPreferences p = ayar(c);
        if (kod.equals(p.getString("kod", "")) && ss.equals(p.getString("ss", "")) && ben != null && ben.mq != null) return;
        p.edit().putString("kod", kod).putString("ss", ss).apply();
        Bekci b = ben;
        if (b != null) b.h.post(b::dinle);
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
        ben = this;
        if (!calisiyor) {
            calisiyor = true;
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            islemci = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "stand:bekci");
            islemci.acquire();
            ekranAlici = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent e) {
                    Kayit.yaz(Intent.ACTION_SCREEN_ON.equals(e.getAction()) ? "Ekran açıldı." : "Ekran kapandı.");
                }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(ekranAlici, f);
            // şarja takılınca/çıkarılınca şarj bilgisi hemen gönderilir
            sarjAlici = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent e) { h.postDelayed(Bekci.this::durumGonder, 3000); }
            };
            IntentFilter sf = new IntentFilter(Intent.ACTION_POWER_CONNECTED);
            sf.addAction(Intent.ACTION_POWER_DISCONNECTED);
            registerReceiver(sarjAlici, sf);
            Sayfa.hazirla(this);
            dinle();
            h.postDelayed(kontrol, 30000);
        }
        return START_STICKY;
    }

    // ---------- bekçinin kendi sunucu bağlantısı ----------
    private void dinle() {
        SharedPreferences p = ayar(this);
        String kod = p.getString("kod", ""), ss = p.getString("ss", "");
        if (kod.isEmpty() || ss.isEmpty()) { Kayit.yaz("Bekçi: sunucu bilgisi yok, sayfa verince dinlemeye başlar."); return; }
        if (mq != null && kod.equals(mqKod) && ss.equals(mqSs)) return;
        if (mq != null) mq.durdur();
        mqKod = kod; mqSs = ss;
        final String konu = "comitok/" + kod + "/goruntu/k";
        final String onek = "comitok/" + kod + "/";
        String[] konular = { konu, onek + "gizlilik", onek + "kamkapi", onek + "durum", onek + "var" };
        Arama.odaDegisti(kod);
        bKonu = "comitok/" + kod + "/goruntu/b";      // bekçinin durumu: stand sayfası "uykuda · uyandırılabilir" gösterir
        iKonu = "comitok/" + kod + "/goruntu/i";      // iPhone'lara giden mesajlar
        mq = new Mqtt(SUNUCU, 8883, true, KULLANICI, ss, konular, bKonu, "{\"b\":\"yok\"}", new Mqtt.Dinleyici() {
            @Override public void mesaj(String k, String govde) {
                if (k.equals(konu)) istekBak(govde);
                else Arama.mesaj(k.substring(onek.length()), govde);
            }
            @Override public void durum(boolean b, String neden) {
                bagli = b;
                if (b) {
                    Mqtt m = mq;
                    if (m != null) m.yayinla(bKonu, durumMetni(), true);
                }
                if (b) { Kayit.yaz("Bekçi sunucuya bağlandı, istekleri dinliyor."); kopukYazildi = false; }
                else if (!kopukYazildi) { Kayit.yaz("Bekçi sunucuya bağlanamadı/koptu: " + neden + " (kendisi yeniden dener)."); kopukYazildi = true; }
            }
        });
        mq.start();
    }

    // Bekçinin durumu (saklı): dinliyor + Pixel şarjı (y = yüzde, s = şarja takılı mı). Stand sayfası göstergede yazar.
    private String durumMetni() {
        JSONObject o = new JSONObject();
        try {
            o.put("b", "dinliyor");
            o.put("z", System.currentTimeMillis());
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int y = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            Intent pil = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            boolean takili = pil != null && pil.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (y >= 0 && y <= 100) o.put("pil", new JSONObject().put("y", y).put("s", takili));
        } catch (Exception e) { }
        sonPil = SystemClock.elapsedRealtime();
        return o.toString();
    }

    private void durumGonder() {
        final Mqtt m = mq;
        if (m == null || !bagli) return;
        final String t = durumMetni();
        ag.execute(() -> m.yayinla(bKonu, t, true));
    }

    // Arama.java için: sunucuya kısa mesaj (ağ işi kendi iş parçacığında)
    static void gonder(final String konuSonu, final String govde, final boolean sakla) {
        final Bekci b = ben;
        if (b == null) return;
        final Mqtt m = b.mq;
        final String kod = b.mqKod;
        if (m == null || kod.isEmpty()) return;
        b.ag.execute(() -> m.yayinla("comitok/" + kod + "/" + konuSonu, govde, sakla));
    }

    static Context baglam() { return ben; }

    private void istekBak(String govde) {
        String ad = "?", t = "", id = "";
        try { JSONObject o = new JSONObject(govde); t = o.optString("t"); ad = o.optString("ad", "?"); id = o.optString("id"); }
        catch (Exception e) { return; }
        if (!"istek".equals(t) || id.isEmpty()) return;
        synchronized (Bekci.class) { bekleyen = govde; bekleyenZaman = System.currentTimeMillis(); }
        final String kim = ad, kimlik = id;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        Ana a = Ana.ben;
        if (pm.isInteractive() && a != null && a.onde) return;   // sayfa zaten önde, kendisi cevaplar
        // iPhone'a hemen haber ver: Pixel uyanıyor, 30 sn beklesin (bu mesaj zaten bekçinin iş parçacığında)
        Mqtt m = mq;
        if (m != null && m.yayinla(iKonu, "{\"t\":\"uyaniyor\",\"id\":" + JSONObject.quote(kimlik) + "}", false))
            Kayit.yaz("Bekçi iPhone'a \"Pixel uyanıyor\" dedi.");
        h.post(() -> uyandir(this, "Bekçi isteği duydu (" + kim + ")"));
    }

    // Sayfa açılınca/ekrana gelince sorar: kaçırdığı istek var mı? (25 sn'den eski istek verilmez)
    static String bekleyenAl() {
        synchronized (Bekci.class) {
            String b = bekleyen; long z = bekleyenZaman;
            bekleyen = null;
            if (b == null || System.currentTimeMillis() - z > 25000) return "";
            return b;
        }
    }

    // Ekranı aç, sayfayı öne getir, kilidi kaldır
    static void uyandir(Context c, String neden) {
        long simdi = SystemClock.elapsedRealtime();
        if (simdi - sonUyandirma < 4000) return;
        sonUyandirma = simdi;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        Ana a = Ana.ben;
        if (pm.isInteractive() && a != null && a.onde) return;
        if (neden != null) Kayit.yaz(neden + ", ekran açılıyor.");
        try {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock k = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "stand:uyan");
            k.acquire(10000);
        } catch (Exception e) { Kayit.yaz("Ekran açma hatası: " + e.getMessage()); }
        if (!Settings.canDrawOverlays(c)) { Kayit.yaz("Sayfa öne getirilemiyor: \"Diğer uygulamaların üzerinde göster\" izni yok."); return; }
        try {
            Intent i = new Intent(c, Ana.class).putExtra("uyan", true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            c.startActivity(i);
        } catch (Exception e) { Kayit.yaz("Sayfa öne getirilemedi: " + e.getMessage()); }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Ana b = Ana.ben;
            if (b == null || !b.onde) Kayit.yaz("Uyarı: 3 sn sonra sayfa hâlâ önde değil (Android engellemiş olabilir).");
        }, 3000);
    }

    private final Runnable kontrol = new Runnable() {
        @Override public void run() {
            if (!calisiyor) return;
            long simdi = SystemClock.elapsedRealtime();
            if (bagli && simdi - sonPil > PIL_ARA) durumGonder();
            if (Sayfa.w == null) Sayfa.hazirla(Bekci.this);
            else if (simdi - sonNabiz > NABIZ_SINIR) {
                Kayit.yaz("Sayfa 5 dk ses vermedi, yenileniyor.");
                sonNabiz = simdi;
                Sayfa.yenile();
            }
            h.postDelayed(this, 30000);
        }
    };

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
        if (ben == this) ben = null;
        h.removeCallbacks(kontrol);
        if (mq != null) { mq.durdur(); mq = null; }
        bagli = false;
        if (ekranAlici != null) { try { unregisterReceiver(ekranAlici); } catch (Exception e) { } }
        if (sarjAlici != null) { try { unregisterReceiver(sarjAlici); } catch (Exception e) { } }
        ag.shutdown();
        if (islemci != null && islemci.isHeld()) islemci.release();
        Sayfa.kapat();
        super.onDestroy();
    }

    // Uygulama son kullanılanlardan kaydırılarak kapatıldı: sayfa bekçide yaşamaya devam eder.
    // Android yine de her şeyi kapatırsa diye 2 sn sonra bekçiyi yeniden başlatma kurulur.
    @Override
    public void onTaskRemoved(Intent kok) {
        if (!Kayit.durdu(this)) {
            Kayit.yaz("Uygulama kaydırılarak kapatıldı; bekçi çalışmaya devam ediyor.");
            try {
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                PendingIntent pi = PendingIntent.getForegroundService(this, 3, new Intent(this, Bekci.class),
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2000, pi);
            } catch (Exception e) { Kayit.yaz("Yeniden başlatma kurulamadı: " + e.getMessage()); }
        }
        super.onTaskRemoved(kok);
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
