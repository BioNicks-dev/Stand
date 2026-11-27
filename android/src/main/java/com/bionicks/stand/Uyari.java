package com.bionicks.stand;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

// 27 Kas: Pixel'den yöneticinin telefonuna ntfy uyarıları (gimbal için).
// Kanal: Gizlilik ayarındaki bildirim konusu (Arama.ntfyKonu()). Konu yoksa sadece kayda yazılır.
// Kimse izlemezken en önemli iki durum: gimbalda açı gelmiyor (tek çare güç tuşu) ve gimbal pili düşük.
final class Uyari {
    private Uyari() { }

    // gimbalın sarı ışığının yandığı nokta (7,09 V, 2 hücre; 26 Kas'ta doğrulandı). Altında bir kez uyarılır.
    private static final int PIL_ESIK_MV = 7090;
    private static final int PIL_YENIDEN_MV = 7300;    // bu değerin üstüne çıkınca (şarj bayrağı olmasa da) uyarı yeniden hazır olur
    private static final long ACI_ARA = 30 * 60 * 1000L;  // aynı "açı gelmiyor" uyarısı en sık 30 dk'da bir

    private static boolean pilUyarildi = false;
    private static int pilDusukSay = 0;       // art arda kaç kontrolde eşik altında (motor yükü anlık düşürmesin diye 2 gerekir)
    private static long sonAci = 0;

    // Bekçi her 30 sn'de çağırır. mv: gimbal toplam pil gerilimi (mV), -1: bilinmiyor
    static void pilKontrol(boolean gimbalBagli, int mv, boolean sarjda) {
        if (!gimbalBagli || mv <= 0) { pilDusukSay = 0; return; }
        if (sarjda || mv >= PIL_YENIDEN_MV) { pilUyarildi = false; pilDusukSay = 0; return; }   // şarja takınca ya da dolunca sıfırlanır
        if (mv < PIL_ESIK_MV) pilDusukSay++; else { pilDusukSay = 0; return; }
        if (pilUyarildi || pilDusukSay < 2) return;
        pilUyarildi = true;
        String v = String.format(java.util.Locale.forLanguageTag("tr"), "%.2f", mv / 1000.0);
        Kayit.yaz("Gimbal: pil düşük (" + v + " V), bildirim gönderiliyor.");
        gonder("Gimbal pili düşük", "Gimbal pili " + v + " V (yaklaşık %20 ve altı). Şarja takın.", 4, "battery");
    }

    // Gimbal.java: açı yeniden bağlanınca da gelmedi (çözüm: gimbalı güç tuşundan kapatıp açmak)
    static void aciGelmiyor() {
        long simdi = System.currentTimeMillis();
        if (simdi - sonAci < ACI_ARA) return;
        sonAci = simdi;
        gonder("Gimbalda açı gelmiyor", "Pixel gimbalı yönetemiyor. Gimbalı güç tuşundan kapatıp açın.", 4, "warning");
    }

    private static void gonder(final String baslik, final String metin, final int oncelik, final String etiket) {
        final String konu = Arama.ntfyKonu();
        if (konu == null || konu.isEmpty()) { Kayit.yaz("Uyarı gönderilemedi: bildirim konusu yok (Ayarlar > Gizlilik). \"" + baslik + "\""); return; }
        new Thread(() -> {
            try {
                JSONObject g = new JSONObject();
                g.put("topic", konu);
                g.put("title", baslik + " · Pixel");
                g.put("message", metin);
                g.put("priority", oncelik);
                g.put("tags", new JSONArray().put(etiket));
                g.put("click", "https://bionicks-dev.github.io/Stand/");
                HttpURLConnection u = (HttpURLConnection) new URL("https://ntfy.sh/").openConnection();
                u.setConnectTimeout(8000); u.setReadTimeout(8000); u.setRequestMethod("POST"); u.setDoOutput(true);
                u.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = u.getOutputStream()) { o.write(g.toString().getBytes(StandardCharsets.UTF_8)); }
                int kod = u.getResponseCode(); u.disconnect();
                Kayit.yaz(kod == 200 ? "Uyarı gönderildi: " + baslik : "Uyarı gönderilemedi (ntfy " + kod + "): " + baslik);
            } catch (Exception e) { Kayit.yaz("Uyarı gönderilemedi (" + e.getClass().getSimpleName() + "): " + baslik); }
        }, "stand-uyari").start();
    }
}
