package com.bionicks.stand;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

// Uygulama 26: Hohem iSteady V3 gimbalını Pixel kendisi yönetir (kart gerekmez).
// Dil (25 Kas, kayıttan çözüldü): AA 55 | L | 00 | grup | komut | veri | toplam | FF
//   L = paket boyu - 2; toplam = L'den verinin sonuna kadar baytların toplamı.
// Komutlar FFE1'e yazılır, cevaplar FFE2'den gelir (FFE3/FFE4 kullanılmaz).
// Hareket: "sahte yüz" (komut 0x60) saniyede ~33 kez; yüz merkezden ne kadar uzaksa gimbal o kadar hızlı döner.
@SuppressLint("MissingPermission")
final class Gimbal {
    private static final UUID SERVIS = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID YAZ = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");
    private static final UUID OKU = UUID.fromString("0000ffe2-0000-1000-8000-00805f9b34fb");
    private static final String AD_ONEK = "SV3";

    // Hohem Joy'un bağlanınca gönderdikleri (aynı sıra). Hareket bunlar gönderilince çalıştı.
    private static final String[] ACILIS = {
        "AA55060000494FFF", "AA5507000034003BFF", "AA550700074A0159FF", "AA55060000353BFF",
        "AA550600075360FF", "AA55060000848AFF", "AA550600075461FF", "AA550600073542FF",
        "AA55070007340042FF", "AA55060000575DFF", "AA55070007D201E1FF", "AA550E0007C10300000000000000D9FF",
        "AA551200079A010000000000000000000000B4FF", "AA551200079A030000000000000000000000B6FF",
        "AA55120007CB000000000000000000000000E4FF", "AA551200079A090001000000000000000000BDFF",
        "AA550E0007C10300000000000000D9FF", "CC666000000000000092",
        "AA551200079A0A0502020200000000000000C8FF", "AA550800072A000039FF", "AA550600075764FF",
        "AA550600005359FF", "AA55060000545AFF", "AA55060000565CFF",
        "AA551200009A010000000000000000000000ADFF", "AA551200009A090000000000000000000000B5FF",
        "AA551200009A0A0502020200000000000000C1FF", "AA550E0000C10300000000000000D2FF",
        "AA55120000CB000000000000000000000000DDFF",
        "AA550700074A0159FF"
    };

    private static final long HAREKET_ARA = 30;        // ms: sahte yüz sıklığı
    private static final long HAREKET_ZAMAN = 300;     // ms: bu süre komut gelmezse gimbal durdurulur (güvenlik)
    private static final int YUZ = 90;                 // sahte yüz kutusu boyu
    private static final String[] MOD_AD = { "PF", "PTF", "L", "POV" };

    private static Gimbal ben;

    private final Context c;
    private final HandlerThread is;
    private final Handler h;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic yazKar;
    private BluetoothLeScanner tarayici;
    private boolean taraniyor = false, calisiyor = false, hazir = false, yaziyor = false;
    private final ArrayDeque<byte[]> kuyruk = new ArrayDeque<>();
    // 26 Kas 20:00: "kumanda gibi" denemesi için FFE3 kanalı (Hohem Joy kullanmıyor) ve ayrı kuyruğu
    private long yazmaBasi = 0;
    private boolean servisAramaBasladi = false;
    private final java.io.ByteArrayOutputStream parca = new java.io.ByteArrayOutputStream();

    // durum (sayfa okur)
    private volatile String durum = "kapali", ad = "", sonHata = "";
    private volatile int pilMv = -1, mod = -1, takipHizi = -1;
    private volatile Boolean sarj = null;
    // 27 Kas: AI kamera ve bekleme (gimbalın kendi bildirimlerinden, kesin olanlar)
    private volatile Boolean aiAcik = null;          // 03 2B / 00 98 01 = açık; 00 99 01 01 = kapandı
    private volatile boolean bekleme = false;        // 00 99 03 01 = beklemede, 00 99 03 02 = uyandı
    private long durZaman = 0, aiHizSon = 0;
    static final int AI_HIZ = 3;                     // elle kullanım bitince takip hızı "çok hızlı" (AI kamera takibi için)
    private volatile long gelen = 0, giden = 0, sonCevap = 0;
    private volatile boolean izinYok = false;

    // Uygulama 27 (Aşama 3a): açı. Gimbalın 00 84 cevabındaki 14 sayı (16 bit, işaretli) ve
    // Pixel'in kendi sensörünün (oyun dönüş vektörü, pusulasız) dörtlüsü yan yana; eşleştirip açıyı çözeceğiz.
    private volatile int[] a84 = null;

    // Uygulama 29 (Aşama 3b): açılar (26 Kas ölçümüyle çözüldü)
    //   yatay = -(a84[2]) / 10  derece, sağa +, -180..180 (gimbalın kendi yön ölçümü, çok yavaş kayar)
    //   eğme  = Pixel'in kendi sensöründen (arka kamera yönünün yükselişi), yukarı +; yerçekimine göre, kaymaz
    //   roll  = (a84[3] - 915) / 10 derece (915 = dik portre)
    private static final double EGME_ALT = -40, EGME_UST = 40;     // eğme güvenli aralığı (derece)
    private static final double YATAY_ARALIK = 330;                // V3 yatay dönüş aralığı (derece)
    private SharedPreferences ayar;
    private volatile boolean solVar = false;
    private volatile double sol = 0;            // sol mekanik sınırın yatay açısı (öğrenilir)
    private volatile boolean gitAktif = false;
    private volatile double hedefY = 0, hedefE = Double.NaN;
    private volatile int gitYonTercih = 0;      // 180'de: +1 sağdan, -1 soldan (sınır bilinmiyorsa)
    private long gitBas = 0, varisZaman = 0;
    private int varisSay = 0; private long varisOlcum = 0;
    private String gitAd = "";
    // takılma (sınır) tespiti
    private double takYatay = 0; private long takZaman = 0; private int takYon = 0;
    private volatile long a84Zaman = 0;
    private volatile float[] q = null;
    private SensorManager sm;
    private final SensorEventListener sensorCb = new SensorEventListener() {
        @Override public void onSensorChanged(SensorEvent e) {
            float[] r = new float[4];
            SensorManager.getQuaternionFromVector(r, e.values);   // w, x, y, z
            q = r;
        }
        @Override public void onAccuracyChanged(Sensor s, int a) { }
    };

    // hareket
    private volatile int hx = 0, hy = 0;
    private volatile long sonHareketKomutu = 0;
    private boolean hareketEdiyor = false;
    private int saniye = 0;

    private Gimbal(Context c) {
        this.c = c.getApplicationContext();
        is = new HandlerThread("gimbal");
        is.start();
        h = new Handler(is.getLooper());
    }

    // ---------- dışarıdan (Bekçi, köprü) ----------
    static synchronized void baslat(Context c) {
        if (ben == null) ben = new Gimbal(c);
        ben.h.post(ben::calis);
    }

    static synchronized void durdur() {
        if (ben == null) return;
        final Gimbal g = ben;
        g.h.post(() -> { g.calisiyor = false; g.kapat("durduruldu"); g.durum = "kapali"; try { if (g.sm != null) g.sm.unregisterListener(g.sensorCb); } catch (Exception e) { } });
    }

    // 27 Kas: bekçinin durumu için kısa bilgi (Stand sayfası, Evi izle kapalıyken de V3 düzenini seçebilsin)
    static String durumKisa() { Gimbal g = ben; return g == null ? "kapali" : g.durum; }
    static int pilKisa() { Gimbal g = ben; return g == null ? -1 : g.pilMv; }            // 27 Kas: bekçi için
    static boolean sarjKisa() { Gimbal g = ben; return g != null && g.sarj != null && g.sarj; }

    static String durumJson() {
        JSONObject o = new JSONObject();
        Gimbal g = ben;
        try {
            if (g == null) { o.put("durum", "kapali"); return o.toString(); }
            o.put("durum", g.izinYok ? "izinYok" : g.durum);
            o.put("ad", g.ad);
            o.put("pilMv", g.pilMv);
            o.put("sarj", g.sarj != null && g.sarj);
            o.put("mod", g.mod);
            o.put("modAd", g.mod >= 0 && g.mod < 4 ? MOD_AD[g.mod] : "");
            o.put("takipHizi", g.takipHizi);
            o.put("cevap", g.sonCevap > 0 && SystemClock.elapsedRealtime() - g.sonCevap < 5000);
            o.put("gelen", g.gelen);
            o.put("giden", g.giden);
            o.put("hareket", g.hareketEdiyor);
            o.put("hata", g.sonHata);
            double yt = g.yatay(), eg = g.egme();
            if (!Double.isNaN(yt)) o.put("yatay", Math.round(yt * 10) / 10.0);
            if (!Double.isNaN(eg)) o.put("egme", Math.round(eg * 10) / 10.0);
            double rl = g.roll(); if (!Double.isNaN(rl)) o.put("roll", Math.round(rl));
            o.put("gidiyor", g.gitAktif);
            o.put("ai", g.aiAcik == null ? -1 : (g.aiAcik ? 1 : 0));
            o.put("bekleme", g.bekleme);
            if (g.sonTani.length() > 0) o.put("tani", g.sonTani);
            if (g.varisZaman > 0 && SystemClock.elapsedRealtime() - g.varisZaman < 4000) o.put("vardi", g.gitAd);
            o.put("sinir", g.solVar);
            o.put("paketBoyu", g.paketBoyu);
            o.put("gucKapatAc", g.aciKurtarma >= 3);
            o.put("aciYok", g.hazir && SystemClock.elapsedRealtime() - Math.max(g.a84Zaman, g.hazirZaman) > 3000);
            o.put("ayar", g.ayarYazi); o.put("ayarliyor", g.testAktif);
            if (g.solVar) o.put("sol", Math.round(g.sol));
            int[] a = g.a84;
            if (a != null && SystemClock.elapsedRealtime() - g.a84Zaman < 3000) {
                JSONArray j = new JSONArray(); for (int v : a) j.put(v); o.put("a84", j);
            }
            float[] r = g.q;
            if (r != null) {
                JSONArray j = new JSONArray(); for (float v : r) j.put(Math.round(v * 10000) / 10000.0); o.put("q", j);
            }
        } catch (Exception e) { }
        return o.toString();
    }

    // x: + sağ, - sol; y: + aşağı, - yukarı (−400..400). Sürekli dönmek için 300 ms'den sık çağrılmalı.
    static void hareket(int x, int y) {
        Gimbal g = ben;
        if (g == null) return;
        g.gitAktif = false;          // elle hareket otomatik gidişi keser
        if (g.testAktif) { g.testAktif = false; g.durYontem = g.testEski; g.istenenHiz = g.testHizEski; g.ayarYazi = "Ayar yarıda kesildi (elle hareket)."; Kayit.yaz("Gimbal ayarı elle hareketle kesildi."); }
        g.hx = sinirla(x); g.hy = sinirla(y);
        g.sonHareketKomutu = SystemClock.elapsedRealtime();
    }

    static void dur() {
        Gimbal g = ben;
        if (g == null) return;
        g.gitAktif = false;
        g.sonHareketKomutu = 0;
    }

    // 26 Kas 16:00: uzaktan bırakınca dur ve "geriMs" önceki açıya (izleyenin ekranda gördüğü yere) dön
    static void durGeri(final int geriMs) {
        final Gimbal g = ben;
        if (g == null) return;
        g.gitAktif = false; g.sonHareketKomutu = 0;
        if (geriMs <= 0) return;
        final long birakma = SystemClock.elapsedRealtime();
        g.h.postDelayed(() -> {
            if (g.gitAktif || g.sonHareketKomutu > 0 || g.testAktif) return;     // bu arada yeni komut geldi
            long hedefT = birakma - geriMs;
            int en = -1; long fark = Long.MAX_VALUE;
            for (int k = 0; k < 128; k++) { if (g.gT[k] == 0) continue; long d = Math.abs(g.gT[k] - hedefT); if (d < fark) { fark = d; en = k; } }
            if (en < 0 || fark > 400) return;
            double yt = g.yatay(), eg = g.egme();
            if (Double.isNaN(yt)) return;
            double dy = sar(g.gY[en] - yt), de = Double.isNaN(eg) || Double.isNaN(g.gE[en]) ? 0 : g.gE[en] - eg;
            if (Math.abs(dy) < 2 && Math.abs(de) < 2) return;                        // zaten yerinde
            if (Math.abs(dy) > 60) return;                                            // mantıksız geri dönüş
            g.hedefY = g.gY[en]; g.hedefE = Math.abs(de) < 2 ? Double.NaN : g.gE[en];
            g.gitYonTercih = 0; g.gitAd = ""; g.gitSessiz = true;
            g.gitBaslat();
        }, 250);                                                                      // önce dursun
    }

    // ---------- Aşama 3b: açıya git ----------
    // egme NaN ise eğme değişmez. ad: kayıt ve "vardı" bildirimi için.
    static void git(final double yatay, final double egme, final String ad) {
        final Gimbal g = ben;
        if (g == null) return;
        g.h.post(() -> {
            g.hedefY = sar(yatay);
            g.hedefE = Double.isNaN(egme) ? Double.NaN : Math.max(EGME_ALT, Math.min(EGME_UST, egme));
            g.gitYonTercih = 0; g.gitAd = ad == null ? "" : ad; g.gitSessiz = false;
            g.gitBaslat();
        });
    }

    static void goreli(final double dy, final double de) {
        final Gimbal g = ben;
        if (g == null) return;
        g.h.post(() -> {
            double y = g.yatay(), e = g.egme();
            if (Double.isNaN(y)) { Kayit.yaz("Gimbal: açı henüz bilinmiyor."); return; }
            g.hedefY = sar(y + dy);
            g.hedefE = Double.isNaN(e) || de == 0 ? Double.NaN : Math.max(EGME_ALT, Math.min(EGME_UST, e + de));
            g.gitYonTercih = dy > 0 ? 1 : (dy < 0 ? -1 : 0); g.gitAd = ""; g.gitSessiz = false;
            g.gitBaslat();
        });
    }

    // yon: +1 sağdan, -1 soldan (sınır izin vermezse öbür yoldan)
    static void yarimTur(final int yon) {
        final Gimbal g = ben;
        if (g == null) return;
        g.h.post(() -> {
            double y = g.yatay();
            if (Double.isNaN(y)) { Kayit.yaz("Gimbal: açı henüz bilinmiyor."); return; }
            g.hedefY = sar(y + 180); g.hedefE = Double.NaN;
            g.gitYonTercih = yon >= 0 ? 1 : -1; g.gitAd = "180°"; g.gitSessiz = false;
            g.gitBaslat();
        });
    }

    // ---------- 26 Kas 15:30: otomatik hareket testi (~50 sn) ----------
    // Her yöne 1,5 sn (hız 150) gider, bırakır, 2,5 sn izler: ne kadar gitti, bırakınca ne kadar kaydı, ne zaman durdu.
    // İki durdurma yöntemi denenir (A: yüz yok, B: önce yüz ortada). Eğme yönü de buradan öğrenilir.
    // {x, y, takip hızı}: hızlı (2) ve çok hızlı (3) karşılaştırılır; durdurma B (önce yüz ortada)
    private static final int[][] TEST = { {150,0,2},{-150,0,2},{0,-150,2},{0,150,2},{150,0,3},{-150,0,3},{0,-150,3},{0,150,3} };
    private static final String[] TEST_AD = { "sağ", "sol", "yukarı", "aşağı" };
    private boolean testAktif = false; private int testNo = 0; private int testEski = 0, testHizEski = 2;
    private double tBasY, tBasE, tDurY, tDurE, tSonY, tSonE; private long tDurZaman, tSakin;
    private final double[] tKayma = new double[8]; private final long[] tSure = new long[8];
    private final StringBuilder testOzet = new StringBuilder();

    static void hareketTesti() {
        final Gimbal g = ben;
        if (g == null) return;
        g.h.post(() -> {
            if (g.testAktif) { Kayit.yaz("Gimbal ayarı zaten sürüyor."); return; }
            String neden = !g.hazir ? "gimbal bağlı değil" : Double.isNaN(g.yatay()) ? "gimbal açısı gelmiyor (paket boyu " + (g.paketBoyu > 0 ? g.paketBoyu : "?") + "); uygulama kendisi toparlamaya çalışıyor, 15 sn sonra tekrar deneyin" : Double.isNaN(g.egme()) ? "Pixel'in eğim sensörü okunmuyor" : null;
            if (neden != null) { g.ayarYazi = "Başlamadı: " + neden + "."; Kayit.yaz("Gimbal ayarı başlamadı: " + neden + "."); return; }
            if (g.mod != 1) { g.gonder(paket(0x00, 0x7C, new int[] { 0, 0, 1, g.istenenHiz })); g.mod = 1; }
            g.testAktif = true; g.testNo = 0; g.testEski = g.durYontem; g.testHizEski = g.istenenHiz; g.gitAktif = false; g.sonHareketKomutu = 0;
            g.testOzet.setLength(0);
            g.ayarYazi = "Ayarlanıyor… (1/8) Gimbala dokunmayın.";
            Kayit.yaz("Gimbal ayarı başladı (~50 sn). Gimbala dokunmayın.");
            g.h.postDelayed(g::testKos, 1500);
        });
    }

    private void testKos() {
        if (!testAktif) return;
        if (testNo >= TEST.length) { testBitir(); return; }
        int[] t = TEST[testNo];
        durYontem = 1;
        if (takipHizi != t[2]) { gonder(paket(0x00, 0x7C, new int[] { 0, 0, 1, t[2] })); takipHizi = t[2]; h.postDelayed(this::testKos, 1000); return; }
        ayarYazi = "Ayarlanıyor… (" + (testNo + 1) + "/8) Gimbala dokunmayın.";
        tBasY = yatay(); tBasE = egme();
        final long bitis = SystemClock.elapsedRealtime() + (t[0] != 0 ? 1500 : 1000);   // eğme 1 sn (uca dayanmasın)
        Runnable it = new Runnable() { @Override public void run() {
            if (!testAktif) return;
            long n = SystemClock.elapsedRealtime();
            if (n < bitis) { hx = sinirla(t[0]); hy = sinirla(t[1]); sonHareketKomutu = n; h.postDelayed(this, 50); return; }
            sonHareketKomutu = 0;                                       // bırak
            tDurY = yatay(); tDurE = egme(); tDurZaman = n; tSakin = n; tSonY = tDurY; tSonE = tDurE;
            h.postDelayed(testIzle, 100);
        } };
        h.post(it);
    }

    private final Runnable testIzle = new Runnable() { @Override public void run() {
        if (!testAktif) return;
        long n = SystemClock.elapsedRealtime();
        double y = yatay(), e = egme();
        boolean yatayTest = TEST[testNo][0] != 0;
        double fark = yatayTest ? Math.abs(sar(y - tSonY)) : Math.abs(e - tSonE);
        if (fark > 0.3) tSakin = n;
        tSonY = y; tSonE = e;
        if (n - tDurZaman < 2500) { h.postDelayed(this, 100); return; }
        int[] t = TEST[testNo];
        int yonNo = t[0] > 0 ? 0 : t[0] < 0 ? 1 : t[1] < 0 ? 2 : 3;
        double gitti, kayma, oteki;
        if (yatayTest) { gitti = sar(tDurY - tBasY); kayma = sar(y - tDurY); oteki = e - tBasE; }
        else { gitti = tDurE - tBasE; kayma = e - tDurE; oteki = sar(y - tBasY); }
        double isaret = yonNo == 0 || yonNo == 2 ? 1 : -1;               // beklenen yön: sağ +, yukarı + (eğme yönü sonra)
        if (!yatayTest) isaret *= egmeIsaret;
        double ileri = kayma * Math.signum(gitti == 0 ? isaret : gitti);  // + = aynı yönde devam etti
        tKayma[testNo] = ileri; tSure[testNo] = tSakin - tDurZaman;
        String satir = "Ayar " + (testNo + 1) + "/8 " + TEST_AD[yonNo] + " (" + (t[2] == 2 ? "hızlı" : "çok hızlı") + "): gitti " + f1(gitti) + "°, bırakınca " + f1(ileri) + "° daha, "
            + String.format(java.util.Locale.US, "%.1f", (tSakin - tDurZaman) / 1000.0) + " sn'de durdu, öbür eksen " + f1(oteki) + "°.";
        Kayit.yaz("Gimbal " + satir);
        if (testNo == 2 && Math.abs(gitti) > 3) {                        // yukarı komutu: eğme ölçümü hangi yöne?
            int yeni = gitti > 0 ? 1 : -1;
            if (yeni != egmeIsaret) { egmeIsaret = yeni; if (ayar != null) ayar.edit().putInt("egmeIsaret", yeni).apply(); }
            testOzet.append("Eğme yönü: yukarı komutu ölçümü ").append(gitti > 0 ? "artırdı (normal)" : "azalttı (ters, düzeltildi)").append(". ");
        }
        testNo++;
        h.postDelayed(Gimbal.this::testKos, 1500);
    } };

    private void testBitir() {
        testAktif = false; sonHareketKomutu = 0;
        double a = 0, b = 0; for (int i = 0; i < 4; i++) { a += tKayma[i]; b += tKayma[i + 4]; }
        int sec = b < a - 1 ? 3 : 2;
        istenenHiz = sec; durYontem = 1;
        gonder(paket(0x00, 0x7C, new int[] { 0, 0, 1, sec }));
        if (ayar != null) ayar.edit().putInt("takipHiz", sec).putInt("durYontem2", 1).apply();
        Kayit.yaz("Gimbal ayarı bitti. " + testOzet + "Bırakınca ortalama kayma: hızlı " + f1(a / 4) + "°, çok hızlı " + f1(b / 4) + "° → " + (sec == 2 ? "hızlı" : "çok hızlı") + " seçildi (kaydedildi).");
        java.text.SimpleDateFormat sf = new java.text.SimpleDateFormat("d MMM HH:mm", new java.util.Locale("tr"));
        ayarYazi = "Ayarlandı (" + sf.format(new java.util.Date()) + "): eğme yönü " + (egmeIsaret > 0 ? "normal" : "ters") + ", takip " + (sec == 2 ? "hızlı" : "çok hızlı") + ", bırakınca kayma ~" + f1(Math.min(a, b) / 4).replace("+", "") + "°.";
        if (ayar != null) ayar.edit().putString("ayarOzet", ayarYazi).apply();
    }

    private static String f1(double d) { return Double.isNaN(d) ? "?" : String.format(java.util.Locale.US, "%+.1f", d).replace('.', ','); }

    static void sinirUnut() {
        final Gimbal g = ben;
        if (g == null) return;
        g.h.post(() -> { g.solVar = false; if (g.ayar != null) g.ayar.edit().putBoolean("solVar2", false).apply(); Kayit.yaz("Gimbal: yatay sınır unutuldu, ilk dayanışta yeniden öğrenilecek."); });
    }

    private void gitBaslat() {
        gitAktif = true; gitBas = SystemClock.elapsedRealtime(); varisSay = 0; varisZaman = 0;
        takZaman = 0;
        akisMax = 0; aciMax = 0; oncekiAci = 0; gitDurKalk = 0; aciBekle = 0; gitEpEnAz = Double.NaN; gitEpZaman = gitBas; gitYIsaret = 0;
        gitDurdu = false; gitBekliyor = false; gitGecme = 0; gitIlkYon = 0; gitSonX = 0; gitYonDegis = 0; kopruMs = 0; sonKopruAdim = 0;
        java.util.Arrays.fill(gitHT, 0); gitHN = 0;
        if (!gitSessiz) Kayit.yaz("Gimbal: " + (gitAd.length() > 0 ? gitAd + " " : "") + "hedefe gidiyor: yatay " + Math.round(hedefY) + "°" + (Double.isNaN(hedefE) ? "" : ", eğme " + Math.round(hedefE) + "°") + ".");
    }

    // 0 = PF, 1 = PTF, 2 = L, 3 = POV
    static void mod(final int m) {
        final Gimbal g = ben;
        if (g == null || m < 0 || m > 3) return;
        g.h.post(() -> {
            int hz = g.takipHizi >= 0 ? g.takipHizi : 1;
            g.gonder(paket(0x00, 0x7C, new int[] { 0, 0, m, hz }));
            Kayit.yaz("Gimbal: mod " + MOD_AD[m] + ".");
        });
    }

    // 0 = yatay, 1 = dikey, 2 = ters yatay (26 Kas denemesiyle düzeltildi)
    static void yon(final int y) {
        final Gimbal g = ben;
        if (g == null || y < 0 || y > 2) return;
        g.h.post(() -> {
            g.gonder(paket(0x00, 0x9A, new int[] { 0x0A, y, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0 }));
            Kayit.yaz("Gimbal: yön " + (y == 1 ? "dikey" : y == 0 ? "yatay" : "ters yatay") + ".");
        });
    }

    static void yenidenBaglan() {
        final Gimbal g = ben;
        if (g == null) return;
        g.h.post(() -> { g.kapat("elle yeniden bağlan"); g.ara(); });
    }

    // ---------- iç işler (hepsi "gimbal" iş parçacığında) ----------
    private void calis() {
        if (calisiyor) return;
        calisiyor = true;
        try {
            ayar = c.getSharedPreferences("gimbal", Context.MODE_PRIVATE);
            solVar = ayar.getBoolean("solVar2", false); sol = ayar.getFloat("sol2", 0f);
            egmeIsaret = ayar.getInt("egmeIsaret", 1) < 0 ? -1 : 1;
            durYontem = ayar.getInt("durYontem2", 1) == 0 ? 0 : 1;        // 16:25 ölçümü: B (önce yüz ortada) eğmede 22° yerine 2° kaydı
            istenenHiz = Math.max(0, Math.min(3, ayar.getInt("takipHiz", 2)));
            ayarYazi = ayar.getString("ayarOzet", "Henüz ayarlanmadı.");
            ongoru = Math.max(0.15, Math.min(0.70, ayar.getFloat("ongoru", 0.30f)));
        } catch (Exception e) { }
        try {
            sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
            Sensor s = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
            if (s == null && sm != null) s = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (s != null) sm.registerListener(sensorCb, s, SensorManager.SENSOR_DELAY_UI, h);
        } catch (Exception e) { }
        h.post(dongu);
        ara();
    }

    private boolean izinVar() {
        if (Build.VERSION.SDK_INT >= 31)
            return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter adaptor() {
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        return bm == null ? null : bm.getAdapter();
    }

    // Gimbalı bul: (1) telefona zaten bağlıysa (deklanşör eşleşmesi) onu kullan, (2) eşli listede ara, (3) tara.
    private void ara() {
        if (!calisiyor || gatt != null) return;
        if (!izinVar()) {
            if (!izinYok) Kayit.yaz("Gimbal: Bluetooth izni yok (Yakındaki cihazlar). Uygulamayı açınca izin istenir.");
            izinYok = true; durum = "izinYok";
            h.postDelayed(this::ara, 10000);
            return;
        }
        izinYok = false;
        BluetoothAdapter a = adaptor();
        if (a == null || !a.isEnabled()) {
            durum = "bluetoothKapali";
            h.postDelayed(this::ara, 10000);
            return;
        }
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        try {
            List<BluetoothDevice> bagliler = bm.getConnectedDevices(BluetoothProfile.GATT);
            for (BluetoothDevice d : bagliler) if (uygun(d)) { baglan(d, "telefona bağlı"); return; }
        } catch (Exception e) { }
        if (!taraniyor) tara();
    }

    private boolean uygun(BluetoothDevice d) {
        try { String n = d.getName(); return n != null && n.startsWith(AD_ONEK); } catch (Exception e) { return false; }
    }

    private final ScanCallback taramaCb = new ScanCallback() {
        @Override public void onScanResult(int tip, final ScanResult r) {
            final BluetoothDevice d = r.getDevice();
            String n = null;
            try { n = d.getName(); } catch (Exception e) { }
            if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
            if (n == null || !n.startsWith(AD_ONEK)) return;
            h.post(() -> { if (taraniyor) { taramaDurdur(); baglan(d, "tarama"); } });
        }
        @Override public void onScanFailed(int kod) { h.post(() -> { taraniyor = false; sonHata = "tarama hatası " + kod; }); }
    };

    private void tara() {
        BluetoothAdapter a = adaptor();
        tarayici = a == null ? null : a.getBluetoothLeScanner();
        if (tarayici == null) { h.postDelayed(this::ara, 10000); return; }
        durum = "araniyor";
        taraniyor = true;
        try {
            ScanSettings s = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
            tarayici.startScan(null, s, taramaCb);
        } catch (Exception e) { taraniyor = false; sonHata = "tarama başlamadı"; }
        // 12 sn bulamazsa dur; eşli listeyi dene, 20 sn sonra yeniden
        h.postDelayed(() -> {
            if (!taraniyor) return;
            taramaDurdur();
            if (gatt == null) eslilerdenDene();
        }, 12000);
    }

    private void taramaDurdur() {
        if (!taraniyor) return;
        taraniyor = false;
        try { if (tarayici != null) tarayici.stopScan(taramaCb); } catch (Exception e) { }
    }

    // Gimbal telefona bağlıyken yayın yapmayabiliyor: eşli (deklanşör) kaydından bağlanmayı dene
    private void eslilerdenDene() {
        BluetoothAdapter a = adaptor();
        if (a != null) {
            try {
                Set<BluetoothDevice> esli = a.getBondedDevices();
                for (BluetoothDevice d : esli) if (uygun(d)) { baglan(d, "eşli kayıt"); return; }
            } catch (Exception e) { }
        }
        h.postDelayed(this::ara, 20000);
    }

    private void baglan(BluetoothDevice d, String nereden) {
        if (gatt != null) return;
        try { ad = d.getName(); } catch (Exception e) { ad = "?"; }
        durum = "baglaniyor";
        Kayit.yaz("Gimbal: " + ad + " bulundu (" + nereden + "), bağlanılıyor.");
        hazir = false; servisAramaBasladi = false; parca.reset();
        gatt = d.connectGatt(c, false, cb, BluetoothDevice.TRANSPORT_LE);
        final BluetoothGatt bu = gatt;
        // 15 sn içinde hazır olmazsa bırak, yeniden ara
        h.postDelayed(() -> { if (gatt == bu && !hazir) { kapat("bağlanma zaman aşımı"); h.postDelayed(this::ara, 3000); } }, 15000);
    }

    private void kapat(String neden) {
        taramaDurdur();
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception e) { }
            try { gatt.close(); } catch (Exception e) { }
            gatt = null;
            if (hazir) Kayit.yaz("Gimbal: bağlantı kapandı (" + neden + ").");
        }
        hazir = false; yazKar = null; kuyruk.clear(); yaziyor = false; hareketEdiyor = false; aiAcik = null; bekleme = false;
        if (calisiyor) durum = "araniyor";
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int st, final int yeni) {
            h.post(() -> {
                if (g != gatt) return;
                if (yeni == BluetoothProfile.STATE_CONNECTED) {
                    // Uygulama 28: Hohem Joy gibi büyük paket izni iste (açı cevabı 36 bayt, varsayılan 20)
                    boolean mtu = false;
                    try { mtu = g.requestMtu(247); } catch (Exception e) { }
                    if (!mtu) { try { g.discoverServices(); } catch (Exception e) { } }
                    else h.postDelayed(() -> { if (g == gatt && !servisAramaBasladi) { servisAramaBasladi = true; try { g.discoverServices(); } catch (Exception e) { } } }, 2500);
                } else if (yeni == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean hazirdi = hazir;
                    kapat("koptu, kod " + st);
                    if (!hazirdi) sonHata = "bağlanamadı (kod " + st + ")";
                    h.postDelayed(Gimbal.this::ara, 3000);
                }
            });
        }

        @Override public void onMtuChanged(final BluetoothGatt g, final int mtu, final int st) {
            h.post(() -> {
                if (g != gatt) return;
                paketBoyu = mtu;
                Kayit.yaz("Gimbal: paket boyu " + mtu + (st == BluetoothGatt.GATT_SUCCESS ? "" : " (hata " + st + ")") + ".");
                if (!servisAramaBasladi) { servisAramaBasladi = true; try { g.discoverServices(); } catch (Exception e) { } }
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, final int st) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(SERVIS);
                BluetoothGattCharacteristic y = s == null ? null : s.getCharacteristic(YAZ);
                BluetoothGattCharacteristic o = s == null ? null : s.getCharacteristic(OKU);
                if (y == null) { sonHata = "FFE1 bulunamadı"; kapat("FFE1 yok"); h.postDelayed(Gimbal.this::ara, 20000); return; }
                yazKar = y;
                // Hohem Joy cevap kanalını açmak için gimbala bir şey yazmıyor; gimbal cevapları kendisi yolluyor.
                // Sadece telefonun tarafında dinlemeyi açıyoruz (gimbala yazma yok, kuyruğu kilitleyemez).
                if (o != null) { try { g.setCharacteristicNotification(o, true); } catch (Exception e) { } }
                hazir = true; durum = "bagli"; sonHata = ""; hazirZaman = SystemClock.elapsedRealtime();
                Kayit.yaz("Gimbal: bağlandı (" + ad + "), açılış paketleri gönderiliyor.");
                for (String p : ACILIS) kuyruk.add(hexten(p));
                kuyruk.add(paket(0x00, 0x7C, new int[] { 0, 0, 1, istenenHiz }));   // PTF + seçili takip hızı (uzaktan kontrol için)
                yazNext();
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic k, int st) {
            h.post(() -> { yaziyor = false; yazNext(); });
        }

        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic k) {
            if (Build.VERSION.SDK_INT >= 33) return;     // yenisi çağrılır
            final byte[] v = k.getValue();
            h.post(() -> cevap(v));
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic k, byte[] v) {
            final byte[] kopya = v == null ? null : v.clone();
            h.post(() -> cevap(kopya));
        }
    };

    // Sıradaki paketi yaz (Android aynı anda tek yazma kabul eder)
    private void yazNext() {
        if (yaziyor || gatt == null || yazKar == null) return;
        byte[] b = kuyruk.poll();
        if (b == null) return;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                ok = gatt.writeCharacteristic(yazKar, b, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == 0;
            } else {
                yazKar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                yazKar.setValue(b);
                ok = gatt.writeCharacteristic(yazKar);
            }
        } catch (Exception e) { ok = false; }
        if (ok) { yaziyor = true; yazmaBasi = SystemClock.elapsedRealtime(); giden++; }
        else { kuyruk.addFirst(b); h.postDelayed(this::yazNext, 20); }
    }

    private void gonder(byte[] b) {
        if (!hazir || b == null) return;
        if (kuyruk.size() > 40) kuyruk.clear();       // tıkandıysa eskileri at
        kuyruk.add(b);
        yazNext();
    }

    // Ana döngü: 30 ms. Hareket akışı, güvenlik durdurması, nabız, takılan yazmayı çözme.
    private final Runnable dongu = new Runnable() {
        @Override public void run() {
            if (!calisiyor) return;
            long simdi = SystemClock.elapsedRealtime();
            if (yaziyor && simdi - yazmaBasi > 1000) { yaziyor = false; yazNext(); }   // geri bildirim gelmediyse devam
            if (hazir) {
                aciKontrol(simdi);
                if (gitAktif) gitAdim(simdi);
                sinirKoru(simdi);
                boolean istek = sonHareketKomutu > 0 && simdi - sonHareketKomutu <= HAREKET_ZAMAN;
                if (istek) {
                    if (!hareketEdiyor) {
                        hareketEdiyor = true; hareketBas = simdi;
                        // PF'de eğme kilitli, L'de hepsi kilitli: yüz takibi eğmiyor. Hareket için PTF gerekir.
                        if (mod == 0 || mod == 2) {
                            gonder(paket(0x00, 0x7C, new int[] { 0, 0, 1, istenenHiz }));
                            Kayit.yaz("Gimbal: mod " + MOD_AD[mod] + " idi, hareket için PTF'ye alındı.");
                            mod = 1;
                        }
                        else if (mod == 1 && takipHizi >= 0 && takipHizi != istenenHiz) gonder(paket(0x00, 0x7C, new int[] { 0, 0, 1, istenenHiz }));
                        gonder(paket(0x03, 0x2B, new int[] { 0, 0 }));     // tracker'ın kendi takibini durdur (Hohem Joy da yapıyor)
                        gonder(takip(0, 0, 0, 0));
                        sonAkis = simdi;
                    }
                    if (kuyruk.size() < 3) {   // açı sorusuna yer kalsın
                        gonder(takip(hx, hy, YUZ, YUZ));
                        if (gitAktif) akisMax = Math.max(akisMax, simdi - sonAkis);
                        sonAkis = simdi;
                    }
                } else if (hareketEdiyor) {
                    // 26 Kas: önce "yüz ortada" yollamak sola ve yukarıda fazla gitmeye yol açıyordu
                    // (gimbalın merkezi tam 0 değil); doğrudan "yüz yok" ile dur.
                    hareketEdiyor = false;
                    durZaman = simdi;
                    kuyruk.clear();
                    if (gitAktif) gitDurKalk++;
                    if (durYontem == 1) { gonder(takip(0, 0, YUZ, YUZ)); gonder(takip(0, 0, YUZ, YUZ)); }
                    gonder(takip(0, 0, 0, 0)); gonder(takip(0, 0, 0, 0)); gonder(takip(0, 0, 0, 0));
                }
                // 27 Kas: elle hareket (ve açılış) bittikten 3 sn sonra takip hızı çok hızlı: AI kamera takibi kaçırmasın.
                // Joystick'e dokununca hareket başında yine seçili hız (istenenHiz) gönderiliyor.
                long sonEl = Math.max(durZaman, hazirZaman);
                if (!hareketEdiyor && !gitAktif && !testAktif && simdi - sonEl > 3000 && aiHizSon < sonEl) {
                    aiHizSon = simdi;
                    if (takipHizi != AI_HIZ) { gonder(paket(0x00, 0x7C, new int[] { 0, 0, mod >= 0 && mod <= 3 ? mod : 1, AI_HIZ })); takipHizi = AI_HIZ; }
                }
                // nabız: saniyede bir, 10 sn'de bir uzun nabız; 30 sn'de bir durum sorusu
                // açı sorusu (00 84 cevabı) 200 ms'de bir
                aciSay += (int) HAREKET_ARA;
                // 26 Kas 17:25: açı sorusu kuyruğun en önüne (hareket paketlerinin arkasında beklemesin)
                if (aciSay >= 150) { aciSay = 0; kuyruk.addFirst(hexten("AA550600078491FF")); yazNext(); }
                saniye += (int) HAREKET_ARA;
                if (saniye >= 1000) {
                    saniye = 0;
                    nabizSay++;
                    gonder(hexten("AA550600074956FF"));
                    if (nabizSay % 2 == 0) gonder(paket(0x07, 0x2B, new int[0]));   // 27 Kas: AI kamera durumu sorusu (sadece okur), cevabı kayda yazılır
                    if (nabizSay % 30 == 0) gonder(hexten("AA550600075764FF"));
                }
            }
            h.postDelayed(this, HAREKET_ARA);
        }
    };
    private void aciKontrol(long simdi) {
        long son = Math.max(a84Zaman, hazirZaman);
        if (simdi - son < 6000 || simdi - aciKurtarmaZaman < 6000) return;
        aciKurtarmaZaman = simdi;
        if (aciKurtarma == 0) {
            aciKurtarma = 1;
            Kayit.yaz("Gimbal: açı gelmiyor (paket boyu " + (paketBoyu > 0 ? paketBoyu : "?") + "), büyük paket yeniden isteniyor.");
            try { if (gatt != null) gatt.requestMtu(247); } catch (Exception e) { }
        } else if (aciKurtarma == 1) {
            aciKurtarma = 2;
            Kayit.yaz("Gimbal: açı hâlâ gelmiyor, yeniden bağlanılıyor.");
            kapat("açı gelmiyor"); h.postDelayed(this::ara, 1500);
        } else if (aciKurtarma == 2) {
            aciKurtarma = 3;
            Kayit.yaz("Gimbal: açı yeniden bağlanınca da gelmedi. Gimbalı güç tuşundan kapatıp açın.");
            Uyari.aciGelmiyor();   // 27 Kas: kimse izlemiyorken telefona ntfy uyarısı (tek çare güç tuşu)
        }
        // 2: bir kez yeniden bağlandı, yine olmazsa döngüye girme (yeniden bağlanınca sayaç sıfırlanır)
    }

    private int nabizSay = 0, aciSay = 0;
    private long hareketBas = 0;
    // 26 Kas 15:30: eğme yönü. +1: y < 0 (yukarı komutu) Pixel'in eğme ölçümünü artırır. Ters çıkarsa kendisi düzeltir ve saklar.
    private int egmeIsaret = 1;
    private double gitEpEnAz = Double.NaN; private long gitEpZaman = 0; private int gitYIsaret = 0;
    // takılma tanısı: hareket akışında ve açı ölçümünde en uzun boşluk, gidişte yeniden başlama sayısı
    private long sonAkis = 0, akisMax = 0, aciMax = 0, oncekiAci = 0; private int gitDurKalk = 0;
    // durdurma yöntemi: 0 = yüz yok (varsayılan), 1 = önce yüz ortada sonra yüz yok (Hohem Joy gibi)
    private int durYontem = 0;
    // 26 Kas 16:40: takip hızı (Follow Speed). Orta (1) gecikmeli: komut 1,5 sn geç başlıyor, bırakınca 1,4 sn devam ediyor.
    private int istenenHiz = 2;
    // 26 Kas 16:50: son gidişin tanısı (Seda sayfasının kaydına da gider); açı beklerken durmadan devam sayısı
    private volatile String sonTani = ""; private int aciBekle = 0;
    // 26 Kas 16:00: "Gimbalı ayarla" ilerleme / sonuç yazısı (panelde görünür)
    private volatile String ayarYazi = "";
    // açı geçmişi (her 00 84 cevabında): "bıraktığın yerde dursun" için
    private final long[] gT = new long[128]; private final double[] gY = new double[128], gE = new double[128]; private int gN = 0;
    private boolean gitSessiz = false;
    // 26 Kas 16:20: açı (00 84) gelmezse kendini toparla: önce paket boyunu yeniden iste, olmazsa yeniden bağlan
    private volatile int paketBoyu = 0; private long hazirZaman = 0; private int aciKurtarma = 0; private long aciKurtarmaZaman = 0;

    // ---------- açılar ----------
    static double sar(double d) { d = ((d + 180) % 360 + 360) % 360 - 180; return d; }

    private boolean aciTaze() { return a84 != null && SystemClock.elapsedRealtime() - a84Zaman < 600; }

    // ---------- 26 Kas 17:25: açı gelmeyince Pixel sensörüyle köprü ----------
    // 180° dönüşte gimbalın açısı 2 sn'ye kadar gelmiyordu (tanı: açı boşluğu 1980 ms) ve gimbal durup kalkıyordu.
    // Pixel gimballa birlikte döner: arka kameranın yatay yönü (pusulasız sensör) gimbal açısını kısa süre taşır.
    // Yön işareti ve fark her gimbal ölçümünde kendiliğinden öğrenilir; öğrenilmeden köprü kullanılmaz.
    private int kIsaret = 0, kOy = 0; private double kOfset = 0, kRefG = Double.NaN, kRefP = Double.NaN; private long kRefT = 0;

    double pixYatay() {
        float[] r = q;
        if (r == null) return Double.NaN;
        double w = r[0], x = r[1], y = r[2], z = r[3];
        double dx = -2 * (x * z + w * y), dy = -2 * (y * z - w * x);     // arka kamera yönünün yatay bileşeni
        if (dx * dx + dy * dy < 0.04) return Double.NaN;                   // dik bakıyorsa yön belirsiz
        return Math.toDegrees(Math.atan2(dx, dy));
    }

    private void kopruOgren(double g, long t) {
        double p = pixYatay();
        if (Double.isNaN(p)) return;
        if (Double.isNaN(kRefG) || t - kRefT > 1500) { kRefG = g; kRefP = p; kRefT = t; }
        else {
            double dg = sar(g - kRefG), dp = sar(p - kRefP);
            if (Math.abs(dg) >= 4) {
                if (Math.abs(dg) < 60 && Math.abs(dp) >= 2) {
                    int s = dg * dp > 0 ? 1 : -1;
                    if (Math.abs(sar(dg - s * dp)) < 2 + 0.2 * Math.abs(dg)) kOy = Math.max(-5, Math.min(5, kOy + s));
                    int yeni = kOy >= 2 ? 1 : kOy <= -2 ? -1 : 0;
                    if (yeni != kIsaret && yeni != 0 && kIsaret == 0) Kayit.yaz("Gimbal: Pixel sensörü açı köprüsü hazır.");
                    kIsaret = yeni;
                }
                kRefG = g; kRefP = p; kRefT = t;
            }
        }
        if (kIsaret != 0) kOfset = sar(g - kIsaret * p);
    }

    // gimbal ölçümü 5 sn'den eski değilse: son ölçümden bu yana Pixel'in döndüğü kadar
    private double yatayKopru(long simdi) {
        if (kIsaret == 0 || a84 == null || simdi - a84Zaman > 5000) return Double.NaN;
        double p = pixYatay();
        return Double.isNaN(p) ? Double.NaN : sar(kIsaret * p + kOfset);
    }

    double yatay() { int[] a = a84; return a == null || SystemClock.elapsedRealtime() - a84Zaman > 3000 ? Double.NaN : sar(-a[2] / 10.0); }

    double roll() { int[] a = a84; return a == null || SystemClock.elapsedRealtime() - a84Zaman > 3000 ? Double.NaN : (a[3] - 915) / 10.0; }

    // Pixel'in sensörü: arka kamera yönünün (cihaz -Z) yükselişi
    double egme() {
        float[] r = q;
        if (r == null) return Double.NaN;
        double w = r[0], x = r[1], y = r[2], z = r[3];
        double fz = -(1 - 2 * (x * x + y * y));      // dönüş matrisinin 3. satırı · (0,0,-1)
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, fz))));
    }

    // Sınır bilinirken: sol sınırdan sağa doğru "u" derecesi (0..330)
    private double u(double yatayDeg) { return ((yatayDeg - sol) % 360 + 360) % 360; }

    // Açıya git: her 30 ms. Uzakken hızlı, yaklaşınca yavaş; durduktan sonra 4 taze ölçüm ±1,5° içindeyse "vardı".
    private void gitAdim(long simdi) {
        // 26 Kas 17:25: gimbal açısı 0,3 sn'den eskiyse Pixel sensörüyle köprü; o da yoksa eskisi gibi 1,5 sn son komutla devam
        boolean kopru = false;
        double yt;
        if (a84 != null && simdi - a84Zaman < 300) yt = yatay();
        else {
            double kb = yatayKopru(simdi);
            if (!Double.isNaN(kb)) { yt = kb; kopru = true; }
            else if (aciTaze()) yt = yatay();
            else {
                if (!gitBekliyor) { gitBekliyor = true; aciBekle++; }
                if ((hx != 0 || hy != 0) && simdi - a84Zaman < 1500) sonHareketKomutu = simdi;
                return;
            }
        }
        gitBekliyor = false;
        if (kopru) { if (sonKopruAdim > 0) kopruMs += simdi - sonKopruAdim; sonKopruAdim = simdi; } else sonKopruAdim = 0;
        double eg = egme();
        if (simdi - gitBas > 30000) { gitAktif = false; sonHareketKomutu = 0; Kayit.yaz("Gimbal: hedefe 30 sn'de ulaşılamadı, durdu. " + tani()); return; }
        double ey;
        if (solVar) {
            double ut = Math.min(YATAY_ARALIK - 2, Math.max(2, u(hedefY))), uc = u(yt);
            if (u(hedefY) > YATAY_ARALIK) ut = u(hedefY) > YATAY_ARALIK + (360 - YATAY_ARALIK) / 2 ? 2 : YATAY_ARALIK - 2;   // ölü bölgedeyse en yakın uca
            ey = ut - uc;
        } else {
            ey = sar(hedefY - yt);
            if (Math.abs(ey) > 175 && gitYonTercih != 0) ey = gitYonTercih * Math.abs(ey);   // tam 180: tercih edilen yön
        }
        double ep = Double.isNaN(hedefE) || Double.isNaN(eg) ? 0 : hedefE - eg;
        // 26 Kas 17:25: varınca sağ-sol yapmasın.
        //  (1) Dönüş hızından bırakınca nerede duracağını tahmin et; hedefe varmadan bırak (öngörü, kendini ayarlar).
        //  (2) Bir kez durduktan sonra hedefe 3°'den yakınsa düzeltme yok; uzaksa tek ve yavaş düzeltme.
        int k = gitHN++ % 16; gitHT[k] = simdi; gitHY[k] = yt; gitHE[k] = Double.isNaN(eg) ? Double.NaN : eg;
        double vy = 0, ve = 0;
        int en = -1; for (int j = 0; j < 16; j++) { if (gitHT[j] == 0 || j == k) continue; long d = simdi - gitHT[j]; if (d >= 200 && d <= 450 && (en < 0 || gitHT[j] > gitHT[en])) en = j; }
        if (en >= 0) {
            double dt = (simdi - gitHT[en]) / 1000.0;
            vy = sar(yt - gitHY[en]) / dt;
            if (!Double.isNaN(eg) && !Double.isNaN(gitHE[en])) ve = (eg - gitHE[en]) / dt;
        }
        double eyT = ey - (vy * ey > 0 ? vy * ongoru : 0), epT = ep - (ve * ep > 0 ? ve * ongoru : 0);
        if (ey * eyT < 0) eyT = 0; if (ep * epT < 0) epT = 0;            // tahmine göre hedefe zaten varacak
        double tol = gitDurdu ? 3.0 : 1.5;
        int xMax = gitDurdu ? 40 : 250, yMax = gitDurdu ? 35 : 150;
        int x = Math.abs(eyT) < tol && (gitDurdu || Math.abs(ey) < 8) ? 0 : (Math.abs(ey) < tol ? 0 : (int) Math.signum(ey) * (int) Math.min(xMax, Math.max(30, 30 + Math.abs(eyT) * 4)));
        int y = Math.abs(epT) < tol && (gitDurdu || Math.abs(ep) < 8) ? 0 : (Math.abs(ep) < tol ? 0 : -egmeIsaret * (int) Math.signum(ep) * (int) Math.min(yMax, Math.max(25, 25 + Math.abs(epT) * 4)));
        // tanı: hedefi ne kadar geçti, yatayda kaç kez yön değiştirdi
        if (gitIlkYon == 0 && Math.abs(ey) >= 3) gitIlkYon = ey > 0 ? 1 : -1;
        if (gitIlkYon != 0) gitGecme = Math.max(gitGecme, -ey * gitIlkYon);
        if (x != 0) { int sx = Integer.signum(x); if (gitSonX != 0 && sx != gitSonX) gitYonDegis++; gitSonX = sx; }
        // eğme ters gidiyorsa (hata en iyi değerinden 4° büyüdü) yönü çevir ve sakla
        int ys = Integer.signum(y);
        if (ys != gitYIsaret) { gitYIsaret = ys; gitEpEnAz = Double.NaN; }   // yön değişince (hedefi geçince) yeniden say
        if (y != 0) {
            if (Double.isNaN(gitEpEnAz) || Math.abs(ep) < gitEpEnAz) { gitEpEnAz = Math.abs(ep); gitEpZaman = simdi; }
            else if (Math.abs(ep) > gitEpEnAz + 4 && simdi - gitEpZaman > 600) {
                egmeIsaret = -egmeIsaret; gitEpEnAz = Double.NaN; gitYIsaret = -ys;
                if (ayar != null) ayar.edit().putInt("egmeIsaret", egmeIsaret).apply();
                Kayit.yaz("Gimbal: eğme ters gidiyordu, eğme yönü çevrildi (kaydedildi).");
                y = -y;
            }
        }
        if (x == 0 && y == 0) {
            gitDurdu = true;
            // varış ancak durduktan sonra gelen 4 TAZE açı ölçümü de hedefteyse sayılır (gecikmeli ölçümle erken "vardı" demesin)
            long olcum = kopru ? -(simdi / 150) : a84Zaman;
            if (olcum != varisOlcum) { varisOlcum = olcum; varisSay++; }
            if (varisSay >= 4) {
                gitAktif = false; sonHareketKomutu = 0; varisZaman = gitSessiz ? 0 : simdi;
                ongoruAyarla();
                if (gitSessiz) Kayit.yaz("Gimbal: bırakılan yere döndü (" + String.format(java.util.Locale.US, "%.1f", (simdi - gitBas) / 1000.0) + " sn).");
                else Kayit.yaz("Gimbal: vardı" + (gitAd.length() > 0 ? " (" + gitAd + ")" : "") + ": yatay " + Math.round(yt) + "° (hedef farkı " + f1(ey) + "°), eğme " + (Double.isNaN(eg) ? "?" : Math.round(eg) + "°") + ", " + String.format(java.util.Locale.US, "%.1f", (simdi - gitBas) / 1000.0) + " sn. " + tani());
            } else sonHareketKomutu = 0;
            return;
        }
        varisSay = 0;
        // yumuşak hız değişimi: 30 ms'de en çok 20 (0 → 250 yaklaşık 0,4 sn); ani hız sıçramaları gimbalı sarsıyordu
        hx = rampa(hx, x); hy = rampa(hy, y); sonHareketKomutu = simdi;
    }

    // 26 Kas 17:25: varış ayarı alanları
    private boolean gitDurdu = false, gitBekliyor = false;
    private double gitGecme = 0; private int gitIlkYon = 0, gitSonX = 0, gitYonDegis = 0;
    private long kopruMs = 0, sonKopruAdim = 0;
    private final long[] gitHT = new long[16]; private final double[] gitHY = new double[16], gitHE = new double[16]; private int gitHN = 0;
    private double ongoru = 0.30;     // sn: bırakınca gimbalın ne kadar daha gideceği (ölçüm gecikmesi + yavaşlama)

    // Hedefi geçtiyse öngörüyü artır, hiç geçmeden durduysa biraz azalt (kaydedilir)
    private void ongoruAyarla() {
        if (gitSessiz || gitIlkYon == 0) return;
        double eski = ongoru;
        if (gitGecme > 1.5) ongoru = Math.min(0.70, ongoru + 0.05);
        else if (gitGecme < 0.3) ongoru = Math.max(0.15, ongoru - 0.02);
        if (ongoru != eski && ayar != null) ayar.edit().putFloat("ongoru", (float) ongoru).apply();
    }


    private static int rampa(int simdiki, int hedef) {
        if (hedef == 0) return 0;
        if (simdiki != 0 && Integer.signum(simdiki) != Integer.signum(hedef)) simdiki = 0;
        if (Math.abs(hedef) <= Math.abs(simdiki)) return hedef;                       // yavaşlama hemen
        int adim = 20;
        int m = Math.max(Math.abs(simdiki), 25) + adim;                               // ilk adım küçük ama hemen kıpırdasın
        return Integer.signum(hedef) * Math.min(Math.abs(hedef), m);
    }

    private String tani() {
        sonTani = "akış boşluğu en çok " + akisMax + " ms, açı boşluğu en çok " + aciMax + " ms, Pixel köprüsü " + String.format(java.util.Locale.US, "%.1f", kopruMs / 1000.0).replace('.', ',') + " sn, açı bekleme " + aciBekle + ", yolda durup kalkma " + gitDurKalk
            + ", hedefi geçme " + f1(Math.max(0, gitGecme)).replace("+", "") + "°, yön değiştirme " + gitYonDegis + ", öngörü " + Math.round(ongoru * 1000) + " ms";
        return "(" + sonTani + ")";
    }

    // Sınır koruması ve öğrenme (elle ve otomatik harekette): eğme ±40° dışına çıkmaz;
    // yatay sınır biliniyorsa 2° kala durur; bilinmiyorsa takılınca (hız ≥ 100, harekete başladıktan 1,5 sn sonra, açı 1,5 sn değişmiyor) öğrenilir.
    private void sinirKoru(long simdi) {
        if (sonHareketKomutu == 0 || simdi - sonHareketKomutu > HAREKET_ZAMAN) { takZaman = 0; return; }
        double eg = egme();
        if (!Double.isNaN(eg)) {
            double ge = egmeIsaret * eg;                 // y < 0 komutunun artırdığı yönde ölçüm
            if (hy < 0 && ge >= EGME_UST) hy = 0;
            if (hy > 0 && ge <= EGME_ALT) hy = 0;
        }
        if (!aciTaze()) return;
        double yt = yatay();
        if (solVar) {
            double uc = u(yt);
            if (hx < 0 && (uc <= 2 || uc > YATAY_ARALIK + 15)) hx = 0;
            if (hx > 0 && uc >= YATAY_ARALIK - 2 && uc <= YATAY_ARALIK + 15) hx = 0;
        }
        if (hx == 0 && hy == 0) { sonHareketKomutu = 0; takZaman = 0; return; }
        int yon = hx > 0 ? 1 : (hx < 0 ? -1 : 0);
        if (Math.abs(hx) < 100 || yon == 0 || simdi - hareketBas < 1500) { takZaman = 0; return; }   // harekete başlarken gecikme var
        if (takZaman == 0 || yon != takYon) { takZaman = simdi; takYatay = yt; takYon = yon; return; }
        if (Math.abs(sar(yt - takYatay)) > 1.0) { takZaman = simdi; takYatay = yt; return; }
        if (simdi - takZaman > 1500) {
            // takıldı: mekanik sınır
            double yeniSol = yon < 0 ? yt : sar(yt - YATAY_ARALIK);
            boolean ilk = !solVar;
            sol = yeniSol; solVar = true; takZaman = 0;
            if (ayar != null) ayar.edit().putBoolean("solVar2", true).putFloat("sol2", (float) sol).apply();
            Kayit.yaz("Gimbal: " + (yon < 0 ? "sol" : "sağ") + " sınıra dayandı (yatay " + Math.round(yt) + "°), sınır " + (ilk ? "öğrenildi" : "tazelendi") + ".");
            hx = 0; if (hy == 0) sonHareketKomutu = 0;
        }
    }

    // Gimbalın cevapları
    // Bölünmüş gelen paketleri birleştir: AA 55 L ... (toplam L + 2 bayt)
    private void cevap(byte[] gelenParca) {
        if (gelenParca == null || gelenParca.length == 0) return;
        parca.write(gelenParca, 0, gelenParca.length);
        byte[] b = parca.toByteArray();
        int i = 0;
        while (b.length - i >= 3) {
            if ((b[i] & 0xFF) != 0xAA || (b[i + 1] & 0xFF) != 0x55) { i++; continue; }   // başı bulana kadar at
            int boy = (b[i + 2] & 0xFF) + 2;
            if (boy < 8 || boy > 300) { i++; continue; }
            if (b.length - i < boy) break;                                                     // kalanı bekle
            byte[] tam = new byte[boy];
            System.arraycopy(b, i, tam, 0, boy);
            paketIsle(tam);
            i += boy;
        }
        parca.reset();
        if (i < b.length && b.length - i < 600) parca.write(b, i, b.length - i);
    }

    private void aiDegis(boolean acik) {
        if (aiAcik != null && aiAcik == acik) return;
        aiAcik = acik; Kayit.yaz(acik ? "Gimbal: AI kamera açık." : "Gimbal: AI kamera kapandı.");
    }
    // 27 Kas: AI kameranın takibini durdur (Hohem Joy'un da gönderdiği 03 2B 00 00)
    static void aiDur() {
        Gimbal g = ben; if (g == null) return;
        g.h.post(() -> { g.gonder(paket(0x03, 0x2B, new int[] { 0, 0 })); Kayit.yaz("Gimbal: AI takibi durduruldu (uzaktan)."); });
    }
    private final java.util.HashMap<Integer, String> sonOlay = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, Long> sonOlayZaman = new java.util.HashMap<>();
    private void olayYaz(int grup, int komut, byte[] v) {
        StringBuilder sb = new StringBuilder();
        for (byte x : v) sb.append(String.format("%02X", x));
        String t = sb.toString();
        int an = (grup << 8) | komut;
        long n = SystemClock.elapsedRealtime();
        Long z = sonOlayZaman.get(an);
        if (t.equals(sonOlay.get(an)) && z != null && n - z < 3000) return;   // aynısı 3 sn içinde tekrar ederse yazma
        sonOlay.put(an, t); sonOlayZaman.put(an, n);
        Kayit.yaz("Gimbal bildirimi " + String.format("%02X %02X", grup, komut) + ": " + t);
    }

    private void paketIsle(byte[] v) {
        if (v == null || v.length < 8) return;
        gelen++;
        sonCevap = SystemClock.elapsedRealtime();
        if ((v[0] & 0xFF) != 0xAA || (v[1] & 0xFF) != 0x55) return;
        int grup = v[4] & 0xFF, komut = v[5] & 0xFF;
        int veriBoy = v.length - 8;            // AA 55 L 00 G C ... S FF
        // 27 Kas: rutin olmayan bütün bildirimler kayda (AI kamera grup 03: 2B durum, 2C jest/takip olayı, 2A; 00 D1, A5, 99, EA ...)
        if (!(grup == 0x00 && (komut == 0x49 || komut == 0x84 || komut == 0x57 || komut == 0x60 || komut == 0x7C))) olayYaz(grup, komut, v);
        if (grup == 0x03 && komut == 0x2B) aiDegis(true);
        else if (grup == 0x00 && komut == 0x98) aiDegis(true);
        else if (grup == 0x00 && komut == 0x99 && veriBoy >= 2) {
            int a = v[6] & 0xFF, b2 = v[7] & 0xFF;
            if (a == 0x01 && b2 == 0x01) aiDegis(false);
            else if (a == 0x03 && (b2 == 0x01 || b2 == 0x02)) {
                boolean yeni = b2 == 0x01;
                if (yeni != bekleme) { bekleme = yeni; Kayit.yaz(yeni ? "Gimbal: beklemeye girdi." : "Gimbal: uyandı."); Bekci.gimbalDegisti(); }
            }
        }
        if (grup == 0x00 && komut == 0x49 && veriBoy >= 4) {
            pilMv = ((v[8] & 0xFF) << 8) | (v[9] & 0xFF);     // muhtemelen pil gerilimi (mV), doğrulanmadı
            // 27 Kas: pil paketinin ilk veri baytı şarj: 01 kablo takılı, 00 değil (iki takma, bir çıkarmayla doğrulandı)
            boolean sj = (v[6] & 0xFF) == 1;
            if (sarj != null && sarj != sj) { Kayit.yaz(sj ? "Gimbal: şarj başladı." : "Gimbal: şarj bitti (kablo çıktı)."); sarj = sj; Bekci.gimbalDegisti(); }
            sarj = sj;
        } else if (grup == 0x00 && komut == 0x84 && veriBoy >= 28) {
            int[] a = new int[14];
            for (int i = 0; i < 14; i++) a[i] = (short) (((v[6 + i * 2] & 0xFF) << 8) | (v[7 + i * 2] & 0xFF));
            long t = SystemClock.elapsedRealtime();
            if (gitAktif && oncekiAci > 0) aciMax = Math.max(aciMax, t - oncekiAci);
            oncekiAci = t;
            a84 = a; a84Zaman = t;
            if (aciKurtarma != 0) { aciKurtarma = 0; Kayit.yaz("Gimbal: açı yeniden geliyor."); }
            int i = gN++ & 127; gT[i] = t; gY[i] = sar(-a[2] / 10.0); gE[i] = egme();
            kopruOgren(gY[i], t);
        } else if (grup == 0x00 && komut == 0x57 && veriBoy >= 2) {
            int m = v[v.length - 4] & 0xFF, hz = v[v.length - 3] & 0xFF;
            if (m <= 3) { if (m != mod && mod >= 0) Kayit.yaz("Gimbal: mod " + MOD_AD[m] + " oldu."); mod = m; }
            if (hz <= 3) takipHizi = hz;
        }
    }

    // ---------- paketler ----------
    static byte[] paket(int grup, int komut, int[] veri) {
        int n = veri.length;
        byte[] b = new byte[n + 8];
        b[0] = (byte) 0xAA; b[1] = 0x55;
        b[2] = (byte) (n + 6); b[3] = 0; b[4] = (byte) grup; b[5] = (byte) komut;
        for (int i = 0; i < n; i++) b[6 + i] = (byte) veri[i];
        int t = 0;
        for (int i = 2; i < 6 + n; i++) t += b[i] & 0xFF;
        b[6 + n] = (byte) t; b[7 + n] = (byte) 0xFF;
        return b;
    }

    // Sahte yüz: 01 02 03 | x (10 bit) << 14 | y (10 bit) << 4 | genişlik | yükseklik | 00 00 00 00
    static byte[] takip(int x, int y, int w, int hh) {
        int v = ((x & 0x3FF) << 14) | ((y & 0x3FF) << 4);
        return paket(0x00, 0x60, new int[] { 1, 2, 3, (v >>> 16) & 0xFF, (v >>> 8) & 0xFF, v & 0xFF, w & 0xFF, hh & 0xFF, 0, 0, 0, 0 });
    }

    static byte[] hexten(String s) {
        if (s == null) return null;
        s = s.replaceAll("[^0-9a-fA-F]", "");
        if (s.length() == 0 || s.length() % 2 != 0) return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }

    private static int sinirla(int a) { return Math.max(-400, Math.min(400, a)); }
}
