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
    private long yazmaBasi = 0;

    // durum (sayfa okur)
    private volatile String durum = "kapali", ad = "", sonHata = "";
    private volatile int pilMv = -1, mod = -1, takipHizi = -1;
    private volatile long gelen = 0, giden = 0, sonCevap = 0;
    private volatile boolean izinYok = false;

    // Uygulama 27 (Aşama 3a): açı. Gimbalın 00 84 cevabındaki 14 sayı (16 bit, işaretli) ve
    // Pixel'in kendi sensörünün (oyun dönüş vektörü, pusulasız) dörtlüsü yan yana; eşleştirip açıyı çözeceğiz.
    private volatile int[] a84 = null;
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

    static String durumJson() {
        JSONObject o = new JSONObject();
        Gimbal g = ben;
        try {
            if (g == null) { o.put("durum", "kapali"); return o.toString(); }
            o.put("durum", g.izinYok ? "izinYok" : g.durum);
            o.put("ad", g.ad);
            o.put("pilMv", g.pilMv);
            o.put("mod", g.mod);
            o.put("modAd", g.mod >= 0 && g.mod < 4 ? MOD_AD[g.mod] : "");
            o.put("takipHizi", g.takipHizi);
            o.put("cevap", g.sonCevap > 0 && SystemClock.elapsedRealtime() - g.sonCevap < 5000);
            o.put("gelen", g.gelen);
            o.put("giden", g.giden);
            o.put("hareket", g.hareketEdiyor);
            o.put("hata", g.sonHata);
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
        g.hx = sinirla(x); g.hy = sinirla(y);
        g.sonHareketKomutu = SystemClock.elapsedRealtime();
    }

    static void dur() {
        Gimbal g = ben;
        if (g == null) return;
        g.sonHareketKomutu = 0;
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

    // 0 = landscape, 1 = portre, 2 = ters landscape (26 Kas denemesiyle düzeltildi)
    static void yon(final int y) {
        final Gimbal g = ben;
        if (g == null || y < 0 || y > 2) return;
        g.h.post(() -> {
            g.gonder(paket(0x00, 0x9A, new int[] { 0x0A, y, 2, 2, 2, 0, 0, 0, 0, 0, 0, 0 }));
            Kayit.yaz("Gimbal: yön " + (y == 1 ? "portre" : y == 0 ? "landscape" : "ters landscape") + ".");
        });
    }

    // Deneme için ham paket (onaltılık)
    static void ham(final String hex) {
        final Gimbal g = ben;
        if (g == null) return;
        final byte[] b = hexten(hex);
        if (b == null) { Kayit.yaz("Gimbal: ham paket geçersiz."); return; }
        g.h.post(() -> g.gonder(b));
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
        hazir = false;
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
        hazir = false; yazKar = null; kuyruk.clear(); yaziyor = false; hareketEdiyor = false;
        if (calisiyor) durum = "araniyor";
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int st, final int yeni) {
            h.post(() -> {
                if (g != gatt) return;
                if (yeni == BluetoothProfile.STATE_CONNECTED) {
                    try { g.discoverServices(); } catch (Exception e) { }
                } else if (yeni == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean hazirdi = hazir;
                    kapat("koptu, kod " + st);
                    if (!hazirdi) sonHata = "bağlanamadı (kod " + st + ")";
                    h.postDelayed(Gimbal.this::ara, 3000);
                }
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
                hazir = true; durum = "bagli"; sonHata = "";
                Kayit.yaz("Gimbal: bağlandı (" + ad + "), açılış paketleri gönderiliyor.");
                for (String p : ACILIS) kuyruk.add(hexten(p));
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
                boolean istek = sonHareketKomutu > 0 && simdi - sonHareketKomutu <= HAREKET_ZAMAN;
                if (istek) {
                    if (!hareketEdiyor) {
                        hareketEdiyor = true;
                        gonder(paket(0x03, 0x2B, new int[] { 0, 0 }));     // tracker'ın kendi takibini durdur (Hohem Joy da yapıyor)
                        gonder(takip(0, 0, 0, 0));
                    }
                    if (kuyruk.size() < 4) gonder(takip(hx, hy, YUZ, YUZ));
                } else if (hareketEdiyor) {
                    hareketEdiyor = false;
                    kuyruk.clear();
                    gonder(takip(0, 0, YUZ, YUZ)); gonder(takip(0, 0, YUZ, YUZ));
                    gonder(takip(0, 0, 0, 0)); gonder(takip(0, 0, 0, 0));
                }
                // nabız: saniyede bir, 10 sn'de bir uzun nabız; 30 sn'de bir durum sorusu
                // açı sorusu (00 84 cevabı) 200 ms'de bir
                aciSay += (int) HAREKET_ARA;
                if (aciSay >= 200) { aciSay = 0; if (kuyruk.size() < 4) gonder(hexten("AA550600078491FF")); }
                saniye += (int) HAREKET_ARA;
                if (saniye >= 1000) {
                    saniye = 0;
                    nabizSay++;
                    gonder(hexten("AA550600074956FF"));
                    if (nabizSay % 30 == 0) gonder(hexten("AA550600075764FF"));
                }
            }
            h.postDelayed(this, HAREKET_ARA);
        }
    };
    private int nabizSay = 0, aciSay = 0;

    // Gimbalın cevapları
    private void cevap(byte[] v) {
        if (v == null || v.length < 8) return;
        gelen++;
        sonCevap = SystemClock.elapsedRealtime();
        if ((v[0] & 0xFF) != 0xAA || (v[1] & 0xFF) != 0x55) return;
        int grup = v[4] & 0xFF, komut = v[5] & 0xFF;
        int veriBoy = v.length - 8;            // AA 55 L 00 G C ... S FF
        if (grup == 0x00 && komut == 0x49 && veriBoy >= 4) {
            pilMv = ((v[8] & 0xFF) << 8) | (v[9] & 0xFF);     // muhtemelen pil gerilimi (mV), doğrulanmadı
        } else if (grup == 0x00 && komut == 0x84 && veriBoy >= 28) {
            int[] a = new int[14];
            for (int i = 0; i < 14; i++) a[i] = (short) (((v[6 + i * 2] & 0xFF) << 8) | (v[7 + i * 2] & 0xFF));
            a84 = a; a84Zaman = SystemClock.elapsedRealtime();
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
