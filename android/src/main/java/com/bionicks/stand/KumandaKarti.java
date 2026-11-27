package com.bionicks.stand;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.UUID;
import org.json.JSONObject;

// 27 Kas: V3 kumanda köprüsü. Bluetooth kartı gimbalın kumandası gibi davranır; Pixel karta bağlanıp
// ışık ve roll komutu yollar. Kart, gimbalın kumandaya yazdığı durum mesajlarını ("H ...") ve kendi
// durumunu ("D g=1 ...") bildirir. Kart sabit adresle yayın yapar, Pixel onu bu adresle bulur.
@SuppressLint("MissingPermission")
final class KumandaKarti {
    private static final String ADRES = "E3:24:17:02:5A:9C";
    private static final UUID SERVIS = UUID.fromString("8e1a0001-5b2c-4f00-9a00-563353544e44");
    private static final UUID KOMUT = UUID.fromString("8e1a0002-5b2c-4f00-9a00-563353544e44");
    private static final UUID DURUM = UUID.fromString("8e1a0003-5b2c-4f00-9a00-563353544e44");

    private static KumandaKarti ben;
    private final Context c;
    private final Handler h;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic komutKar;
    private boolean calisiyor = false, hazir = false, yaziyor = false;
    private final ArrayDeque<byte[]> kuyruk = new ArrayDeque<>();
    private volatile boolean gimbalKartta = false;
    private volatile int isik = -1;   // 27 Kas: gimbalın kumandaya bildirdiği ışık (tür 03 mesajı 13. bayt: 01 açık, 00 kapalı; denemeyle doğrulandı). -1 bilinmiyor
    private volatile long sonDurum = 0;
    private volatile String surum = "", sonH2 = "", sonH3 = "";

    private KumandaKarti(Context c) {
        this.c = c.getApplicationContext();
        HandlerThread t = new HandlerThread("kumandaKarti");
        t.start();
        h = new Handler(t.getLooper());
    }

    static synchronized void baslat(Context c) {
        if (ben == null) ben = new KumandaKarti(c);
        ben.h.post(() -> { if (!ben.calisiyor) { ben.calisiyor = true; ben.baglan(); } });
    }

    static synchronized void durdur() {
        if (ben == null) return;
        final KumandaKarti k = ben;
        k.h.post(() -> { k.calisiyor = false; k.kapat(); });
    }

    // Sayfanın gönderebileceği komutlar (başkası kabul edilmez)
    static void komut(String s) {
        KumandaKarti k = ben;
        if (k == null || s == null) return;
        if (!(s.equals("isik") || s.equals("rollbas+") || s.equals("rollbas-") || s.equals("rollbit")
              || s.equals("roll+") || s.equals("roll-"))) return;
        final byte[] b = s.getBytes(StandardCharsets.UTF_8);
        k.h.post(() -> { if (k.hazir) { if (k.kuyruk.size() > 10) k.kuyruk.clear(); k.kuyruk.add(b); k.yazNext(); } });
    }

    // b: Pixel karta bağlı; g: gimbal karta bağlı (kart 15 sn'de bir söyler)
    static String durumJson() {
        JSONObject o = new JSONObject();
        KumandaKarti k = ben;
        try {
            boolean b = k != null && k.hazir;
            boolean taze = k != null && SystemClock.elapsedRealtime() - k.sonDurum < 40000;
            o.put("b", b);
            o.put("g", b && taze && k.gimbalKartta);
            if (b && taze && k.gimbalKartta && k.isik >= 0) o.put("i", k.isik == 1);
        } catch (Exception e) { }
        return o.toString();
    }

    private void baglan() {
        if (!calisiyor || gatt != null) return;
        BluetoothManager bm = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter a = bm == null ? null : bm.getAdapter();
        if (a == null || !a.isEnabled()) { h.postDelayed(this::baglan, 30000); return; }
        try {
            BluetoothDevice d = Build.VERSION.SDK_INT >= 33
                ? a.getRemoteLeDevice(ADRES, BluetoothDevice.ADDRESS_TYPE_RANDOM)
                : a.getRemoteDevice(ADRES);
            // autoConnect: kart görününce Android kendisi bağlar (tarama gerekmez, pil yemez)
            gatt = d.connectGatt(c, true, cb, BluetoothDevice.TRANSPORT_LE);
        } catch (Exception e) { gatt = null; h.postDelayed(this::baglan, 30000); }
    }

    private void kapat() {
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Exception e) { }
            try { gatt.close(); } catch (Exception e) { }
            gatt = null;
        }
        if (hazir) Kayit.yaz("Kumanda kartı: bağlantı kapandı.");
        hazir = false; komutKar = null; kuyruk.clear(); yaziyor = false; gimbalKartta = false;
    }

    private final BluetoothGattCallback cb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int st, final int yeni) {
            h.post(() -> {
                if (g != gatt) return;
                if (yeni == BluetoothProfile.STATE_CONNECTED) {
                    // 27 Kas: varsayılan paket 20 bayt; gimbal durum mesajı (40+ harf) kesiliyordu
                    boolean m = false; try { m = g.requestMtu(247); } catch (Exception e) { }
                    if (!m) { try { g.discoverServices(); } catch (Exception e) { } }
                }
                else if (yeni == BluetoothProfile.STATE_DISCONNECTED) { kapat(); h.postDelayed(KumandaKarti.this::baglan, 5000); }
            });
        }
        @Override public void onMtuChanged(final BluetoothGatt g, final int mtu, final int st) {
            h.post(() -> { if (g == gatt) { try { g.discoverServices(); } catch (Exception e) { } } });
        }
        @Override public void onServicesDiscovered(final BluetoothGatt g, final int st) {
            h.post(() -> {
                if (g != gatt) return;
                BluetoothGattService s = g.getService(SERVIS);
                BluetoothGattCharacteristic k = s == null ? null : s.getCharacteristic(KOMUT);
                BluetoothGattCharacteristic d = s == null ? null : s.getCharacteristic(DURUM);
                if (k == null) { Kayit.yaz("Kumanda kartı: bağlandı ama köprü servisi yok (kartta eski kod olabilir)."); kapat(); h.postDelayed(KumandaKarti.this::baglan, 60000); return; }
                komutKar = k;
                if (d != null) { try { g.setCharacteristicNotification(d, true); } catch (Exception e) { } }
                hazir = true;
                Kayit.yaz("Kumanda kartı: bağlandı.");
                kuyruk.add("merhaba".getBytes(StandardCharsets.UTF_8));   // kart Pixel'i tanısın, durumunu yollasın
                yazNext();
            });
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic k, int st) {
            h.post(() -> { yaziyor = false; yazNext(); });
        }
        @SuppressWarnings("deprecation")
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic k) {
            if (Build.VERSION.SDK_INT >= 33) return;
            final byte[] v = k.getValue();
            h.post(() -> geldi(v));
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic k, byte[] v) {
            final byte[] kopya = v == null ? null : v.clone();
            h.post(() -> geldi(kopya));
        }
    };

    private void yazNext() {
        if (yaziyor || gatt == null || komutKar == null) return;
        byte[] b = kuyruk.poll();
        if (b == null) return;
        boolean ok;
        try {
            if (Build.VERSION.SDK_INT >= 33) ok = gatt.writeCharacteristic(komutKar, b, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
            else { komutKar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); komutKar.setValue(b); ok = gatt.writeCharacteristic(komutKar); }
        } catch (Exception e) { ok = false; }
        if (ok) { yaziyor = true; h.postDelayed(() -> { if (yaziyor) { yaziyor = false; yazNext(); } }, 1000); }
        else { kuyruk.addFirst(b); h.postDelayed(this::yazNext, 50); }
    }

    private void geldi(byte[] v) {
        if (v == null) return;
        String s = new String(v, StandardCharsets.UTF_8).trim();
        if (s.startsWith("D ")) {
            sonDurum = SystemClock.elapsedRealtime();
            boolean g = s.contains("g=1");
            if (!g) isik = -1;
            if (g != gimbalKartta) Kayit.yaz("Kumanda kartı: gimbal " + (g ? "karta bağlı" : "karta bağlı değil") + ".");
            gimbalKartta = g;
            int i = s.indexOf(" s=");
            String yeniSurum = i >= 0 ? s.substring(i + 3) : "";
            if (!yeniSurum.equals(surum)) { surum = yeniSurum; Kayit.yaz("Kumanda kartı sürümü: " + surum + "."); }
        } else if (s.startsWith("H ")) {
            // gimbal -> kumanda durum mesajı (değişince gelir). Işık baytını bulmak için kayda yazılır.
            String hx = s.substring(2);
            // AA CC 12 00 00 03 ... : bayt 12 ışık
            if (hx.length() >= 40 && hx.startsWith("AACC12") && hx.startsWith("03", 10)) {
                int yeni = hx.startsWith("01", 24) ? 1 : (hx.startsWith("00", 24) ? 0 : -1);
                if (yeni >= 0 && yeni != isik) { isik = yeni; Kayit.yaz("Kumanda kartı: gimbal ışığı " + (yeni == 1 ? "açık" : "kapalı") + "."); }
            }
            boolean tur3 = hx.length() > 11 && hx.startsWith("03", 10);
            if (tur3 ? !hx.equals(sonH3) : !hx.equals(sonH2)) {
                if (tur3) sonH3 = hx; else sonH2 = hx;
                Kayit.yaz("Kumanda kartı: gimbal durumu " + hx);
            }
        } else if (s.length() > 0) {
            Kayit.yaz("Kumanda kartı: " + s);
        }
    }
}
