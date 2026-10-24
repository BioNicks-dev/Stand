package com.bionicks.stand;

import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.json.JSONObject;

// Uygulama 7: Pixel'e WhatsApp'tan gelen görüntülü arama.
//  - Yönetici (varsayılan "Ben Türkiye") ararsa hemen açar.
//  - Diğer kişiler (varsayılan "Seda"): stand sayfasındaki gizlilik kuralı aynen uygulanır
//    (yönetici evdeyse VEYA saat aralığındaysa ntfy bildirimi + Engelle, 15 sn; ev kamerası kapalıysa reddeder).
//  - Başka biri: hiç dokunmaz.
// Bildirimlere sadece düğmesine basarak dokunur; HİÇBİR BİLDİRİMİ SİLMEZ (8-9 Ekim: silmek WhatsApp'ı bozmuştu).
// Kararlar ana iş parçacığında, ağ işleri ayrı iş parçacığında.
final class Arama {
    private static final Handler h = new Handler(Looper.getMainLooper());
    private static final long BEKLE = 15000, RED = 60000, TAZE = 90000, ODA_SURE = 9000;
    private static final String KIMLIK = "pixel_wa";          // odadaki görünür adı: "Seda (WhatsApp)"

    // sunucudan gelenler
    private static JSONObject gk = null;                      // gizlilik ayarı (comitok/<oda>/gizlilik)
    private static boolean kamKapali = false;                 // yönetici ev kamerasını kapattı mı (comitok/<oda>/kamkapi)
    private static boolean xrBagli = false; private static long durumZaman = 0;   // XR karta bağlı mı (comitok/<oda>/durum)
    private static final Map<String, long[]> yoneticiler = new HashMap<>();       // id -> {zaman, önde, izliyor}
    private static final Map<String, String[]> yonCevap = new HashMap<>();        // id -> {gkRed, gkOk}

    // süren işler
    private static Bekleyen bek = null;                       // sorulan, cevap beklenen arama
    private static String aktifKey = null, aktifAd = null;    // açtığımız arama
    private static boolean aktifYon = false, aktifSurdu = false;
    private static final Map<String, Long> islenen = new HashMap<>();   // aynı arama bildirimi tekrar gelirse

    private static final class Bekleyen {
        String id, ad, key; PendingIntent ac, red; long bas; volatile boolean bitti; HttpURLConnection dinleyen;
    }

    // ---------- ayarlar (Pixel'in kamera sayfasından gelir) ----------
    private static SharedPreferences ayar(Context c) { return c.getApplicationContext().getSharedPreferences("stand", Context.MODE_PRIVATE); }

    static String ayarAl(Context c) {
        SharedPreferences p = ayar(c);
        JSONObject o = new JSONObject();
        try {
            o.put("acik", p.getBoolean("wa_acik", true));
            o.put("yon", p.getString("wa_yon", "Ben Türkiye"));
            o.put("diger", p.getString("wa_diger", "Seda"));
        } catch (Exception e) { }
        return o.toString();
    }

    static void ayarYaz(Context c, String json) {
        try {
            JSONObject o = new JSONObject(json);
            ayar(c).edit().putBoolean("wa_acik", o.optBoolean("acik", true))
                    .putString("wa_yon", o.optString("yon", "Ben Türkiye").trim())
                    .putString("wa_diger", o.optString("diger", "Seda").trim()).apply();
            Kayit.yaz("WhatsApp ayarı kaydedildi: " + (o.optBoolean("acik", true) ? "açık" : "kapalı")
                    + ", yönetici \"" + o.optString("yon") + "\", diğer \"" + o.optString("diger") + "\".");
        } catch (Exception e) { }
    }

    // ---------- sunucudan gelen mesajlar (bekçinin iş parçacığında gelir, ana iş parçacığına aktarılır) ----------
    static void odaDegisti(String kod) {
        h.post(() -> { gk = null; kamKapali = false; xrBagli = false; durumZaman = 0; yoneticiler.clear(); yonCevap.clear(); });
    }

    static void mesaj(final String konu, final String govde) {
        h.post(() -> {
            JSONObject m = null;
            try { m = new JSONObject(govde); } catch (Exception e) { }
            if ("gizlilik".equals(konu)) { gk = m; return; }
            if ("kamkapi".equals(konu)) {
                boolean once = kamKapali;
                kamKapali = m != null && m.optBoolean("k", false);
                if (once != kamKapali) Kayit.yaz("WhatsApp: ev kamerası " + (kamKapali ? "kapalı, diğer kişilerin araması reddedilecek." : "açık."));
                return;
            }
            if (m == null) return;
            if ("durum".equals(konu)) {
                if (m.optBoolean("cevrimdisi", false)) { xrBagli = false; durumZaman = 0; return; }
                xrBagli = dogru(m.opt("iphone"));   // sayfadaki gibi: alan doluysa XR karta bağlı
                durumZaman = System.currentTimeMillis();
                return;
            }
            if ("var".equals(konu)) {
                String id = m.optString("id");
                if (id.isEmpty() || KIMLIK.equals(id)) return;
                if (m.optInt("y", 0) != 1 && !m.optBoolean("y", false)) return;   // sadece yöneticiler
                yoneticiler.put(id, new long[]{ System.currentTimeMillis(), m.optBoolean("on", true) ? 1 : 0, m.optInt("iz", 0) == 1 || m.optBoolean("iz", false) ? 1 : 0 });
                yonCevap.put(id, new String[]{ m.optString("gkRed"), m.optString("gkOk") });
                Bekleyen b = bek;
                if (b != null && !b.bitti) {
                    if (b.id.equals(m.optString("gkRed"))) sonuc(false, "yönetici sayfadan engelledi");
                    else if (b.id.equals(m.optString("gkOk"))) sonuc(true, "yönetici sayfadan izin verdi");
                }
            }
        });
    }

    // ---------- bildirimden gelenler (Bildirim.java, ana iş parçacığı) ----------
    static void gelen(Context c, String key, String ad, String yazi, PendingIntent ac, PendingIntent red) {
        long n = System.currentTimeMillis();
        temizle(n);
        if (islenen.containsKey(key) || (bek != null && key.equals(bek.key)) || key.equals(aktifKey)) return;
        islenen.put(key, n);
        SharedPreferences p = ayar(c);
        String kim = ad == null ? "" : ad;
        if (!p.getBoolean("wa_acik", true)) { Kayit.yaz("WhatsApp: " + kim + " arıyor; otomatik açma kapalı, dokunulmadı."); return; }
        String y = yazi == null ? "" : yazi.toLowerCase(Locale.ROOT);
        if (!(y.contains("video") || y.contains("görüntülü"))) { Kayit.yaz("WhatsApp: " + kim + " sesli arıyor; sadece görüntülü aramalar açılır, dokunulmadı."); return; }
        String nk = ad(kim);
        if (!nk.isEmpty() && nk.equals(ad(p.getString("wa_yon", "Ben Türkiye")))) {
            Kayit.yaz("WhatsApp: yönetici (" + kim + ") arıyor, açılıyor.");
            cevapla(key, kim, ac, true);
            return;
        }
        boolean diger = false;
        for (String d : p.getString("wa_diger", "Seda").split(",")) if (!nk.isEmpty() && nk.equals(ad(d))) diger = true;
        if (!diger) { Kayit.yaz("WhatsApp: \"" + kim + "\" arıyor; izinli kişilerden değil, dokunulmadı."); return; }

        if (kamKapali) { Kayit.yaz("WhatsApp: " + kim + " arıyor ama ev kamerası kapalı (yönetici kapattı), reddediliyor."); bas(red, "Reddet"); return; }
        if (n < p.getLong("wa_red_" + nk, 0) && gkZ().equals(p.getString("wa_redZ_" + nk, ""))) {
            Kayit.yaz("WhatsApp: " + kim + " arıyor; yönetici az önce engellemişti (1 dk), reddediliyor."); bas(red, "Reddet"); return;
        }
        if (n < p.getLong("wa_izin_" + nk, 0) && gkZ().equals(p.getString("wa_izinZ_" + nk, ""))) {
            Kayit.yaz("WhatsApp: " + kim + " arıyor; izin süresi içinde, sormadan açılıyor."); cevapla(key, kim, ac, false); return;
        }
        String neden = sorNedeni();
        if (neden == null) {
            izinYaz(c, nk);
            Kayit.yaz("WhatsApp: " + kim + " arıyor; gizlilik kuralı sormuyor (" + sormamaNedeni() + "), açılıyor.");
            cevapla(key, kim, ac, false);
            return;
        }
        sor(c, key, kim, ac, red, neden);
    }

    // Bağlandıktan sonra WhatsApp'ın \"süren görüşme\" bildirimi (aşama 2 için de kayda yazılır)
    static void suruyor(String key, String ad) {
        if (key.equals(aktifKey) || (aktifKey != null && ad != null && ad(ad).equals(ad(aktifAd)))) {
            aktifKey = key;
            if (!aktifSurdu) { aktifSurdu = true; Kayit.yaz("WhatsApp: görüşme bağlandı (" + aktifAd + ")."); }
        }
    }

    // WhatsApp'ın arama bildirimi kalktı; başka arama bildirimi kalmadıysa iş biter
    static void kalkti(String key, boolean baskaVar) {
        islenen.remove(key);
        Bekleyen b = bek;
        if (b != null && !b.bitti && key.equals(b.key)) { Kayit.yaz("WhatsApp: " + b.ad + " beklerken vazgeçti (arama bitti)."); bitir(); return; }
        if (aktifKey != null && !baskaVar) {
            Kayit.yaz("WhatsApp: görüşme bitti (" + aktifAd + ").");
            aktifKey = null; aktifAd = null; aktifSurdu = false;
            if (!aktifYon) varlik(null, false, false);
        }
    }

    // ---------- gizlilik kuralı (index.html'deki gkSorulmali ile aynı) ----------
    private static JSONObject a() {
        JSONObject o = new JSONObject();
        try {
            o.put("acik", true); o.put("evde", true); o.put("hi", true); o.put("bas", "19:00"); o.put("bit", "09:00");
            o.put("hs", true); o.put("sayfa", true); o.put("sure", 15); o.put("konu", "");
            if (gk != null) for (Iterator<String> i = gk.keys(); i.hasNext(); ) { String k = i.next(); o.put(k, gk.get(k)); }
        } catch (Exception e) { }
        return o;
    }
    private static String gkZ() { return gk == null ? "0" : String.valueOf(gk.optLong("z", 0)); }

    private static boolean yonSayfaAcik() {
        long n = System.currentTimeMillis();
        for (long[] v : yoneticiler.values()) if (n - v[0] < ODA_SURE && v[1] == 1) return true;
        return false;
    }
    private static boolean yonIzliyor() {
        long n = System.currentTimeMillis();
        for (long[] v : yoneticiler.values()) if (n - v[0] < ODA_SURE && v[1] == 1 && v[2] == 1) return true;
        return false;
    }

    // null: sorulmaz. Değilse sorma sebebi ("evdesin, saat aralığında")
    private static String sorNedeni() {
        JSONObject a = a();
        if (!a.optBoolean("acik", true) || a.optString("konu").isEmpty()) return null;
        if (yonIzliyor()) return null;
        if (a.optBoolean("sayfa", true) && yonSayfaAcik()) return null;
        StringBuilder s = new StringBuilder();
        if (a.optBoolean("evde", true) && xrBagli && System.currentTimeMillis() - durumZaman < TAZE) s.append("evdesin (XR bağlı)");
        if (saatte(a)) { if (s.length() > 0) s.append(", "); s.append("saat aralığında"); }
        return s.length() > 0 ? s.toString() : null;
    }
    private static String sormamaNedeni() {
        JSONObject a = a();
        if (!a.optBoolean("acik", true)) return "kural kapalı";
        if (a.optString("konu").isEmpty()) return "bildirim konusu yok";
        if (yonIzliyor()) return "yönetici Evi izle'de";
        if (a.optBoolean("sayfa", true) && yonSayfaAcik()) return "yöneticinin stand sayfası açık";
        return "evde değilsin, saat aralığı dışında";
    }

    private static int dak(String t) {
        try { String[] p = t.split(":"); return Integer.parseInt(p[0].trim()) * 60 + Integer.parseInt(p[1].trim()); } catch (Exception e) { return 0; }
    }
    // Florida saatiyle. Aralık gece yarısını geçebilir: akşam kısmı Pazar-Cuma, sabah kısmı Pazartesi-Cumartesi.
    private static boolean saatte(JSONObject a) {
        ZonedDateTime f = ZonedDateTime.now(ZoneId.of("America/New_York"));
        int g = f.getDayOfWeek().getValue() % 7, dk = f.getHour() * 60 + f.getMinute();
        if (a.optBoolean("hs", true) && (g == 0 || g == 6)) return true;
        if (!a.optBoolean("hi", true)) return false;
        int b = dak(a.optString("bas", "19:00")), e = dak(a.optString("bit", "09:00"));
        if (b == e) return false;
        if (b < e) return g >= 1 && g <= 5 && dk >= b && dk < e;
        if (dk >= b) return g <= 5;
        if (dk < e) return g >= 1;
        return false;
    }

    private static void izinYaz(Context c, String nk) {
        int sure = a().optInt("sure", 15);
        ayar(c).edit().putLong("wa_izin_" + nk, System.currentTimeMillis() + sure * 60000L).putString("wa_izinZ_" + nk, gkZ()).apply();
    }

    // ---------- sor: ntfy bildirimi + yöneticinin sayfasındaki kutu, 15 sn ----------
    private static void sor(final Context c, String key, String kim, PendingIntent ac, PendingIntent red, String neden) {
        final Bekleyen b = new Bekleyen();
        b.id = Long.toString((long) (Math.random() * 1e12), 36); b.ad = kim; b.key = key; b.ac = ac; b.red = red; b.bas = System.currentTimeMillis();
        bek = b;
        final String konu = a().optString("konu");
        Kayit.yaz("WhatsApp: " + kim + " arıyor; yöneticiye bildirim gönderiliyor, 15 sn bekleniyor (" + neden + ").");
        varlikDongu(b);
        new Thread(() -> {
            try {
                JSONObject g = new JSONObject();
                g.put("topic", konu);
                g.put("title", kim + " WhatsApp'tan arıyor · Pixel");
                g.put("message", "Pixel çalıyor. 15 sn içinde engellemezsen açılır.");
                g.put("priority", 4);
                g.put("tags", new org.json.JSONArray().put("telephone_receiver"));
                g.put("click", "https://bionicks-dev.github.io/Stand/");
                g.put("actions", new org.json.JSONArray().put(new JSONObject().put("action", "http").put("label", "Engelle")
                        .put("url", "https://ntfy.sh/" + konu + "-cevap").put("method", "POST").put("body", "engelle " + b.id).put("clear", true)));
                HttpURLConnection u = (HttpURLConnection) new URL("https://ntfy.sh/").openConnection();
                u.setConnectTimeout(8000); u.setReadTimeout(8000); u.setRequestMethod("POST"); u.setDoOutput(true);
                u.setRequestProperty("Content-Type", "application/json");
                try (OutputStream o = u.getOutputStream()) { o.write(g.toString().getBytes(StandardCharsets.UTF_8)); }
                int kod = u.getResponseCode(); u.disconnect();
                if (kod != 200) Kayit.yaz("WhatsApp: bildirim gönderilemedi (ntfy " + kod + "). Yine 15 sn beklenir.");
            } catch (Exception e) { Kayit.yaz("WhatsApp: bildirim gönderilemedi (" + e.getClass().getSimpleName() + "). Yine 15 sn beklenir."); }
        }, "stand-ntfy").start();
        // bildirimdeki Engelle düğmesi <konu>-cevap konusuna yazar; onu dinle
        new Thread(() -> {
            try {
                HttpURLConnection u = (HttpURLConnection) new URL("https://ntfy.sh/" + konu + "-cevap/json?since=" + (b.bas / 1000 - 5)).openConnection();
                u.setConnectTimeout(8000); u.setReadTimeout(20000);
                b.dinleyen = u;
                BufferedReader r = new BufferedReader(new InputStreamReader(u.getInputStream(), StandardCharsets.UTF_8));
                String satir;
                while (!b.bitti && (satir = r.readLine()) != null) {
                    try {
                        JSONObject m = new JSONObject(satir);
                        if ("message".equals(m.optString("event")) && ("engelle " + b.id).equals(m.optString("message").trim()))
                            h.post(() -> { if (bek == b) sonuc(false, "bildirimden engelledi"); });
                    } catch (Exception e) { }
                }
            } catch (Exception e) { }
        }, "stand-ntfy-dinle").start();
        h.postDelayed(() -> { if (bek == b && !b.bitti) sonuc(true, "15 sn doldu"); }, BEKLE);
    }

    // Beklerken yöneticinin sayfasında "Seda (WhatsApp) bağlanmak istiyor: Engelle / İzin ver" kutusu çıksın
    private static void varlikDongu(final Bekleyen b) {
        if (bek != b || b.bitti) return;
        varlik(b.ad, true, false, b.id);
        h.postDelayed(() -> varlikDongu(b), 3000);
    }

    private static void sonuc(boolean izin, String neden) {
        Bekleyen b = bek; if (b == null || b.bitti) return;
        Context c = Bekci.baglam();
        String nk = ad(b.ad);
        bitir();
        if (!izin) {
            Kayit.yaz("WhatsApp: yönetici " + neden + ", " + b.ad + " reddediliyor.");
            if (c != null) ayar(c).edit().putLong("wa_red_" + nk, System.currentTimeMillis() + RED).putLong("wa_izin_" + nk, 0).putString("wa_redZ_" + nk, gkZ()).apply();
            bas(b.red, "Reddet");
            return;
        }
        Kayit.yaz("WhatsApp: izin (" + neden + "), " + b.ad + " açılıyor.");
        if (c != null) izinYaz(c, nk);
        cevapla(b.key, b.ad, b.ac, false);
    }

    private static void bitir() {
        Bekleyen b = bek; if (b == null) return;
        b.bitti = true; bek = null;
        final HttpURLConnection u = b.dinleyen;
        if (u != null) new Thread(() -> { try { u.disconnect(); } catch (Exception e) { } }).start();
        varlik(null, false, false);
    }

    private static void cevapla(String key, String kim, PendingIntent ac, boolean yon) {
        aktifKey = key; aktifAd = kim; aktifYon = yon; aktifSurdu = false;
        if (!bas(ac, "Cevapla")) { aktifKey = null; aktifAd = null; return; }
        if (!yon) varlikAktif(key);
        // 8 sn içinde "süren görüşme" bildirimi görülmezse kayda uyarı (yedek: MacroDroid'deki Open Camera makrosu)
        h.postDelayed(() -> {
            if (key.equals(aktifKey) && !aktifSurdu)
                Kayit.yaz("Uyarı: WhatsApp araması 8 sn içinde bağlanmış görünmüyor (" + kim + "). Gerekirse MacroDroid'de \"Open Camera\" makrosunu geri açın.");
        }, 8000);
    }

    // Seda WhatsApp'tan bağlıyken odada "Seda (WhatsApp)" görünsün (yöneticinin sayfasında ding + kim burada)
    private static void varlikAktif(final String key) {
        if (aktifKey == null || aktifYon) return;
        varlik(aktifAd, true, true);
        h.postDelayed(() -> varlikAktif(key), 3000);
    }

    private static void varlik(String ad, boolean on, boolean iz) { varlik(ad, on, iz, null); }
    private static void varlik(String ad, boolean on, boolean iz, String gkId) {
        try {
            JSONObject m = new JSONObject();
            m.put("id", KIMLIK); m.put("cihaz", "Pixel");
            m.put("ad", ad == null ? "WhatsApp" : (ad + " (WhatsApp)"));
            m.put("on", on);
            if (iz) m.put("iz", 1);
            if (gkId != null) m.put("gk", gkId);
            Bekci.gonder("var", m.toString(), false);
        } catch (Exception e) { }
    }

    private static boolean bas(PendingIntent pi, String ne) {
        if (pi == null) { Kayit.yaz("WhatsApp: bildirimde \"" + ne + "\" düğmesi bulunamadı."); return false; }
        try { pi.send(); Kayit.yaz("WhatsApp: \"" + ne + "\" düğmesine basıldı."); return true; }
        catch (Exception e) { Kayit.yaz("WhatsApp: \"" + ne + "\" düğmesine basılamadı: " + e.getClass().getSimpleName()); return false; }
    }

    private static boolean dogru(Object v) {
        if (v == null || v == JSONObject.NULL) return false;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).doubleValue() != 0;
        String t = v.toString();
        return !t.isEmpty() && !"false".equals(t);
    }

    // Ad karşılaştırma: görünmez yön işaretleri atılır, büyük/küçük harf fark etmez
    static String ad(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]", "").trim().replaceAll("\\s+", " ").toLowerCase(new Locale("tr"));
    }

    private static void temizle(long n) {
        Iterator<Map.Entry<String, Long>> i = islenen.entrySet().iterator();
        while (i.hasNext()) if (n - i.next().getValue() > 120000) i.remove();
    }
}
