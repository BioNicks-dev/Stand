package com.bionicks.stand;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Santral: Pixel birini WhatsApp'tan GÖRÜNTÜLÜ arar, karşı taraf kabul edince hemen kapatır ("Leave call").
//  - Uygulama 14-17 (parça A): Pixel sayfasındaki deneme düğmesi. Rehberde yoksa WhatsApp sohbet listesinden.
//  - Uygulama 18 (parça B): stand sayfası, sayfası kapalı birini ararken comitok/<oda>/arama konusuna
//    {t:"waAra",id,kime} yazar (3 sn'de bir). Pixel her adımı aynı konuya {t:"waDurum",id,d,m} ile bildirir:
//    alindi, ariyor, caliyor, baglandi, kapandi, red, cevapyok, mesgul (Pixel kamerada), hata. Arayan vazgeçerse {t:"iptal",id}.
// Erişilebilirlik ve Kişiler izni gerekir.
final class Santral {
    private static final Handler h = new Handler(Looper.getMainLooper());
    static final String MIME_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call";
    static final String MIME_SOHBET = "vnd.android.cursor.item/vnd.com.whatsapp.profile";
    // 25 Eki: 0,5 sn'de kapatınca WhatsApp "just answered your call. Continue?" diye soruyor; 1,2 sn'de sormuyordu
    private static final int ARALIK = 700, EN_UZUN = 70000, KAPAT_BEKLE = 1200;
    private static String sonKutu = "";
    private static final int KAPAT_X = 876, KAPAT_Y = 2189;   // bölüm 24: görüşme ekranında kapat

    private static int no = 0;                 // her yeni arama eskisini geçersiz kılar
    private static String kim = null, durum = "";
    private static long bas = 0, baglandiZaman = 0;
    private static boolean caldiGoruldu = false, onaylandi = false;
    private static boolean sohbetYolu = false, videoBasildi = false;
    private static boolean listeYolu = false, aramaYazildi = false, aramaKutusuAcildi = false;   // uygulama 17: rehberde yoksa WhatsApp sohbet listesinden   // uygulama 16: yedek yol (sohbeti aç, görüntülü arama simgesine dokun)
    private static int dokum = 0, yokSay = 0;
    private static String sonImza = "";
    private static PowerManager.WakeLock kilit = null;

    // ---------- parça B: stand sayfasından gelen istekler ----------
    private static String aId = null;                        // şu anki isteğin kimliği (deneme düğmesinde null)
    private static long caliyorBas = 0;
    private static final java.util.LinkedHashMap<String, String> sonuclar = new java.util.LinkedHashMap<>();   // id -> son durum (tekrar sorulursa)
    static volatile boolean kameraMesgul = false; static volatile long mesgulZaman = 0;   // sayfa 3 sn'de bir söyler

    static void mesaj(final String govde) {
        h.post(() -> {
            org.json.JSONObject m;
            try { m = new org.json.JSONObject(govde); } catch (Exception e) { return; }
            String t = m.optString("t"), id = m.optString("id");
            if (id.isEmpty() || "pixel".equals(m.optString("kimden"))) return;
            if ("waAra".equals(t)) {
                if (id.equals(aId)) return;                                   // zaten bunu arıyorum
                String once = sonuclar.get(id);
                if (once != null) { durumGonder(id, once, ""); return; }      // bitmiş: sonucu yeniden söyle
                istek(id, m.optString("kime"), m.optString("ad"));
            } else if ("waSon".equals(t)) {
                String[] a = sonArama.get(konuAd(m.optString("kime")));
                boolean taze = a != null && System.currentTimeMillis() - Long.parseLong(a[2]) < 3 * 60000L;
                try {
                    org.json.JSONObject o = new org.json.JSONObject().put("t", "waSonCevap").put("id", id).put("kimden", "pixel").put("ad", "Pixel");
                    if (taze) o.put("arama", a[0]).put("d", a[1]).put("k", a[3]);
                    Bekci.gonder("arama", o.toString(), false);
                } catch (Exception e) { }
                Kayit.yaz("Santral: " + m.optString("kime") + " sayfasını WhatsApp'tan açtı; " + (taze ? "son arama gönderildi (" + a[1] + ")." : "bekleyen arama yok."));
            } else if ("waSor".equals(t)) {
                // aranan kişinin sayfası (parça C): "WhatsApp kapandı mı?"
                if (id.equals(aId)) durumGonder(id, aktifD.isEmpty() ? "ariyor" : aktifD, "");
                else { String once = sonuclar.get(id); if (once != null) durumGonder(id, once, ""); }
            } else if ("iptal".equals(t) && id.equals(aId)) {
                yaz("arayan vazgeçti, WhatsApp araması kapatılıyor.");
                kapatBas(Bekci.baglam(), no);
                bitir(Bekci.baglam(), "iptal");
            }
        });
    }

    private static void istek(String id, String kime, String arayan) {
        Context c = Bekci.baglam();
        if (c == null) return;
        if (kim != null) { durumGonder(id, "hata", "Pixel şu an başka bir WhatsApp araması yapıyor"); sonucYaz(id, "hata"); return; }
        if (kameraMesgul && System.currentTimeMillis() - mesgulZaman < 15000) {
            Kayit.yaz("Santral: " + arayan + " → " + kime + " isteği geldi ama Pixel kamerada (Evi izle), çaldırılmadı.");
            durumGonder(id, "mesgul", ""); sonucYaz(id, "mesgul"); return;
        }
        String wa = esle(c, kime);
        Kayit.yaz("Santral: " + (arayan.isEmpty() ? "?" : arayan) + ", " + kime + " kişisini arıyor; WhatsApp'tan \"" + wa + "\" çaldırılacak.");
        durumGonder(id, "alindi", "");
        aId = id; aktifD = "alindi";
        // 25 Eki: not sadece bağlantı; her kişinin kendi konusu (…-ara-<ad>), kestirme sadece en son notu ister
        notKime = kime;
        sonArama.put(konuAd(kime), new String[]{ id, "alindi", String.valueOf(System.currentTimeMillis()), arayan == null ? "" : arayan });
        // "&" yok: ntfy'nin json cevabında \u0026 olarak yazılıyor, kestirme bağlantıyı metinden ayıklıyor
        notBirak(kime, "https://bionicks-dev.github.io/Stand/?wa=" + id + "~" + Uri.encode(arayan == null ? "" : arayan));
        ara(c, wa);
    }

    // Pixel sayfasındaki eşleştirme: "Seda=Seda, Berat=Ben Türkiye"; listede yoksa aynı ad
    private static String esle(Context c, String ad) {
        String l = c.getApplicationContext().getSharedPreferences("stand", Context.MODE_PRIVATE).getString("wa_esle", "");
        for (String p : l.split(",")) {
            int i = p.indexOf('=');
            if (i > 0 && Arama.ad(p.substring(0, i)).equals(Arama.ad(ad))) { String w = p.substring(i + 1).trim(); if (!w.isEmpty()) return w; }
        }
        return ad;
    }

    // Parça C: iPhone'daki "Stand WhatsApp" kestirmesi kendi konusunda son 2 dk'da not var mı diye bakar
    // (ntfy.sh/<konu>-ara-<ad>/json?poll=1&since=2m; cevap boşsa bir şey yapmaz), varsa stand sayfasını ?wa=son ile açar.
    private static String aktifD = "", notKime = null;
    private static final java.util.HashMap<String, Long> sonNot = new java.util.HashMap<>();   // kişi -> son bağlantı notu (geç "bitti" yenisini ezmesin)
    // 25 Eki: kestirme ntfy cevabını okuyamıyor; sadece "not var mı" diye bakıp ?wa=son açıyor. Sayfa da
    // {t:"waSon",kime} ile Pixel'e son aramayı sorar: kişi -> {id, durum, zaman}
    private static final java.util.HashMap<String, String[]> sonArama = new java.util.HashMap<>();
    // stand index.html'deki gsKonuAd ile aynı: küçük harf, Türkçe harfler sadeleşir, sadece a-z 0-9
    static String konuAd(String ad) {
        String k = ad == null ? "" : ad.trim().toLowerCase(new Locale("tr"));
        k = k.replace('ç', 'c').replace('ğ', 'g').replace('ı', 'i').replace('ö', 'o').replace('ş', 's').replace('ü', 'u');
        return k.replaceAll("[^a-z0-9]", "");
    }
    private static void notBirak(String kime, final String metin) {
        final String ka = konuAd(kime);
        if (ka.isEmpty()) return;
        if (!"bitti".equals(metin)) sonNot.put(ka, System.currentTimeMillis());
        final String konu = Arama.ntfyKonu().isEmpty() ? "" : Arama.ntfyKonu() + "-ara-" + ka;
        if (konu.isEmpty()) { Kayit.yaz("Santral: gizlilik bildirim konusu yok; iPhone'daki kestirme için not bırakılamadı (stand sayfası > Ayarlar > Gizlilik > Oluştur)."); return; }
        new Thread(() -> {
            try {
                java.net.HttpURLConnection u = (java.net.HttpURLConnection) new java.net.URL("https://ntfy.sh/" + konu).openConnection();
                u.setConnectTimeout(8000); u.setReadTimeout(8000); u.setRequestMethod("POST"); u.setDoOutput(true);
                try (java.io.OutputStream o = u.getOutputStream()) { o.write(metin.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                int k = u.getResponseCode(); u.disconnect();
                if (k != 200) Kayit.yaz("Santral: kestirme notu yazılamadı (ntfy " + k + ").");
            } catch (Exception e) { Kayit.yaz("Santral: kestirme notu yazılamadı (" + e.getClass().getSimpleName() + ")."); }
        }, "stand-santral-not").start();
    }

    private static void durumGonder(String id, String d, String m) {
        if (id == null) return;
        if (id.equals(aId)) { aktifD = d; if (notKime != null) { String[] a = sonArama.get(konuAd(notKime)); if (a != null && id.equals(a[0])) a[1] = d; } }
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("t", "waDurum"); o.put("id", id); o.put("d", d); if (m != null && !m.isEmpty()) o.put("m", m);
            o.put("kimden", "pixel"); o.put("ad", "Pixel");
            Bekci.gonder("arama", o.toString(), false);
        } catch (Exception e) { }
    }
    private static void bildir(String d) { durumGonder(aId, d, ""); }
    private static void sonucYaz(String id, String d) { sonuclar.put(id, d); while (sonuclar.size() > 20) sonuclar.remove(sonuclar.keySet().iterator().next()); }

    // başlatırken hata: kayda yaz, arayana söyle
    private static void hata(String m) {
        yaz(m);
        if (aId != null) { durumGonder(aId, "hata", m); sonucYaz(aId, "hata"); aId = null; }
        birak();
    }

    static boolean rehberIzni(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED; }

    // Pixel sayfası düğmenin altında gösterir: süren aramanın durumu ya da son 30 sn'deki sonuç
    private static String son = ""; private static long sonZaman = 0;
    private static void yaz(String m) { Kayit.yaz("Santral: " + m); son = m; sonZaman = System.currentTimeMillis(); }
    static boolean mesgul() { return kim != null; }

    static String durum() {
        if (kim != null) return kim + ": " + durum;
        return System.currentTimeMillis() - sonZaman < 30000 ? son : "";
    }

    // ---------- başlat ----------
    static void ara(final Context c, final String ad) {
        h.post(() -> {
            if (kim != null) { yaz("zaten bir arama sürüyor (" + kim + ")."); return; }
            if (ad == null || ad.trim().isEmpty()) { hata("aranacak ad boş."); return; }
            if (!rehberIzni(c)) { hata("Kişiler izni yok. Pixel sayfasındaki \"Kişiler izni ver\" düğmesine dokunun."); return; }
            if (Erisim.ben == null) { hata("erişilebilirlik kapalı; arama kapatılamaz, başlatılmadı."); return; }
            long[] veri = bul(c, ad.trim());
            ekranAc(c);
            sohbetYolu = false; videoBasildi = false; listeYolu = false; aramaYazildi = false; aramaKutusuAcildi = false; sonKutu = "";
            if (veri == null) {
                // uygulama 17: rehberde WhatsApp satırı yok (ör. kişi sadece WhatsApp'a kaydedilmiş): WhatsApp'ı ana ekranından aç
                Intent l0 = c.getPackageManager().getLaunchIntentForPackage(Erisim.WA);
                if (l0 == null) { hata("WhatsApp açılamadı."); return; }
                l0.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                try { c.getApplicationContext().startActivity(l0); }
                catch (Exception e) { hata("WhatsApp açılamadı: " + e.getClass().getSimpleName()); return; }
                listeYolu = true;
                kim = ad.trim(); durum = "sohbet aranıyor"; bas = System.currentTimeMillis(); baglandiZaman = 0;
                caldiGoruldu = false; onaylandi = false; dokum = 0; yokSay = 0; sonImza = "";
                final int n0 = ++no;
                Kayit.yaz("Santral: \"" + kim + "\" rehberde yok; WhatsApp sohbet listesinden aranacak.");
                bildir("ariyor"); caliyorBas = 0;
                h.postDelayed(() -> izle(c, n0), 1500);
                return;
            }
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, String.valueOf(veri[0])), MIME_VIDEO)
                    .setPackage(Erisim.WA)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.getApplicationContext().startActivity(i); }
            catch (Exception e) {
                Kayit.yaz("Santral: doğrudan görüntülü arama açılamadı: " + e.getClass().getSimpleName() + " · " + kisaHata(e)
                        + (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED ? " (Telefon izni yok)" : ""));
                // yedek: kişinin WhatsApp sohbetini aç, görüntülü arama simgesine erişilebilirlikle dokun
                long sohbet = sohbetBul(c, veri[1]);
                if (sohbet < 0) { hata("WhatsApp sohbeti de bulunamadı, arama başlatılamadı."); return; }
                Intent s2 = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, String.valueOf(sohbet)), MIME_SOHBET)
                        .setPackage(Erisim.WA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.getApplicationContext().startActivity(s2); sohbetYolu = true; Kayit.yaz("Santral: yedek yol: WhatsApp sohbeti açıldı, görüntülü arama simgesine dokunulacak."); }
                catch (Exception e2) { hata("WhatsApp sohbeti de açılamadı: " + e2.getClass().getSimpleName() + " · " + kisaHata(e2)); return; }
            }
            kim = ad.trim(); durum = "başlatılıyor"; bas = System.currentTimeMillis(); baglandiZaman = 0;
            caldiGoruldu = false; onaylandi = false; dokum = 0; yokSay = 0; sonImza = "";
            final int n = ++no;
            yaz("" + kim + " WhatsApp'tan görüntülü aranıyor.");
            bildir("ariyor"); caliyorBas = 0;
            h.postDelayed(() -> izle(c, n), 1500);
        });
    }

    private static String kisaHata(Exception e) { String m = e.getMessage(); if (m == null) return ""; m = m.replace('\n', ' '); return m.length() > 220 ? m.substring(0, 220) + "…" : m; }

    // Kişinin WhatsApp sohbet satırı (aynı kişi kimliği)
    private static long sohbetBul(Context c, long kisi) {
        try (Cursor k = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI, new String[]{ ContactsContract.Data._ID },
                ContactsContract.Data.MIMETYPE + "=? AND " + ContactsContract.Data.CONTACT_ID + "=?", new String[]{ MIME_SOHBET, String.valueOf(kisi) }, null)) {
            if (k != null && k.moveToFirst()) return k.getLong(0);
        } catch (Exception e) { }
        return -1;
    }

    // Rehberde WhatsApp görüntülü arama satırını ada göre bul: {satır, kişi}
    private static long[] bul(Context c, String ad) {
        String aranan = Arama.ad(ad);
        List<String> adlar = new ArrayList<>();
        try (Cursor k = c.getContentResolver().query(ContactsContract.Data.CONTENT_URI,
                new String[]{ ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME, ContactsContract.Data.CONTACT_ID },
                ContactsContract.Data.MIMETYPE + "=?", new String[]{ MIME_VIDEO }, null)) {
            if (k != null) while (k.moveToNext()) {
                String g = k.getString(1);
                if (aranan.equals(Arama.ad(g))) return new long[]{ k.getLong(0), k.getLong(2) };
                if (g != null && adlar.size() < 15) adlar.add(g);
            }
        } catch (Exception e) { yaz("rehber okunamadı (" + e.getClass().getSimpleName() + ")."); return null; }
        Kayit.yaz("Santral: rehberde WhatsApp'ı olan kişiler: " + (adlar.isEmpty() ? "hiç yok" : String.join(", ", adlar)) + ".");
        return null;
    }

    // ---------- izleme döngüsü ----------
    private static void izle(Context c, int n) {
        if (n != no || kim == null) return;
        Erisim s = Erisim.ben;
        long t = System.currentTimeMillis() - bas;
        if (s == null) { yaz("erişilebilirlik kapandı, izleme bitti."); bitir(c, "yarıda kaldı"); return; }
        AccessibilityNodeInfo kok = s.waKok();
        List<AccessibilityNodeInfo> l = new ArrayList<>();
        if (kok != null) Erisim.topla(kok, l, 0);

        // ekran değiştiyse kayda dök (öğrenmek için; en fazla 10 kez). Sadece arama ekranı ve soru kutuları:
        // sohbet listesi ve sohbetler kayda yazılmaz (mesajlar görünmesin)
        String imza = imza(l);
        if (aramaEkrani(l) && !imza.equals(sonImza) && dokum < 10) { sonImza = imza; dokum++; s.dok("Santral " + (t / 1000) + " sn", kok); }

        // WhatsApp bir kutu gösterdiyse (ör. "You can't call your own number") yazısını kayda geçir
        AccessibilityNodeInfo mesaj = bulKutuYazisi(l);
        if (mesaj != null) {
            String m = Erisim.str(mesaj.getText());
            AccessibilityNodeInfo onay = bulOnay(l);
            if (m.toLowerCase(Locale.ROOT).contains("own number")) {
                yaz("\"" + kim + "\" Pixel'in kendi WhatsApp numarası, aranamaz. Rehberde bu kişinin numarasını kontrol edin.");
                if (onay != null) onay.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                bitir(c, "kendi numarası");
                return;
            }
            if (!m.equals(sonKutu)) { sonKutu = m; Kayit.yaz("Santral: WhatsApp kutusu: \"" + kisa(m) + "\""); }
            // kabul edildikten sonra çıkan her kutu (ör. "...just answered your call. Do you want to continue the call?"): kapat
            String mk = m.toLowerCase(Locale.ROOT);
            if (baglandiZaman > 0 || mk.contains("just answered") || mk.contains("continue the call")) {
                AccessibilityNodeInfo kapa = bulKutuKapat(l);
                if (kapa != null) {
                    boolean ok = kapa.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    Kayit.yaz("Santral: kutuda \"" + Erisim.ad(kapa) + "\" seçildi" + (ok ? "." : " (tıklama kabul edilmedi)."));
                }
                h.postDelayed(() -> izle(c, n), ARALIK);
                return;
            }
        }

        // rehberde yoksa: sohbet listesinde satırı bul ve sohbete gir
        if (listeYolu && !sohbetYolu) {
            AccessibilityNodeInfo satir = bulListeSatiri(l, kim);
            if (satir != null) {
                boolean ok = satir.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!ok) { Rect b = new Rect(); satir.getBoundsInScreen(b); ok = s.dokun(b.centerX(), b.centerY()); }
                sohbetYolu = true; durum = "sohbet açılıyor"; bas = System.currentTimeMillis();
                Kayit.yaz("Santral: sohbet listesinde \"" + kim + "\" bulundu, sohbet açılıyor" + (ok ? "." : " (dokunma gönderilemedi)."));
            } else if (!aramaYazildi && t > 2500) {
                AccessibilityNodeInfo yazi = bulYaziKutusu(l);
                if (yazi != null) {
                    android.os.Bundle a = new android.os.Bundle();
                    a.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, kim);
                    aramaYazildi = yazi.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a);
                    Kayit.yaz("Santral: listede görünmedi, WhatsApp aramasına \"" + kim + "\" yazıldı" + (aramaYazildi ? "." : " (yazılamadı)."));
                } else if (!aramaKutusuAcildi) {
                    AccessibilityNodeInfo ara = bulAramaCubugu(l);
                    if (ara != null) { aramaKutusuAcildi = true; ara.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                }
            }
            if (!sohbetYolu && t > 12000) { yaz("WhatsApp sohbet listesinde \"" + kim + "\" bulunamadı."); bitir(c, "bulunamadı"); return; }
            h.postDelayed(() -> izle(c, n), ARALIK);
            return;
        }

        AccessibilityNodeInfo kapat = bulKapat(l);
        if (sohbetYolu && !videoBasildi && kapat == null) {
            AccessibilityNodeInfo v = bulVideo(l);
            if (v != null) {
                videoBasildi = true;
                boolean ok = v.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (!ok) { Rect b = new Rect(); v.getBoundsInScreen(b); ok = s.dokun(b.centerX(), b.centerY()); }
                Kayit.yaz("Santral: sohbetteki \"" + Erisim.ad(v) + "\" simgesine dokunuldu" + (ok ? "." : " (gönderilemedi)."));
                bas = System.currentTimeMillis();   // süreler aramanın başladığı andan
            } else if (t > 8000) {
                yaz("sohbette görüntülü arama simgesi bulunamadı (ekran satırlarına bakın).");
                bitir(c, "başlatılamadı");
                return;
            }
        }
        // "aransın mı?" sorusu çıkarsa onayla
        if (kapat == null && !onaylandi && (!sohbetYolu || videoBasildi)) {
            AccessibilityNodeInfo onay = bulOnay(l);
            if (onay != null) {
                onaylandi = true;
                boolean ok = onay.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                yaz("WhatsApp onay istedi, \"" + Erisim.ad(onay) + "\" seçildi" + (ok ? "." : " (tıklama kabul edilmedi)."));
            }
        }

        if (baglandiZaman == 0) {
            boolean caliyor = caliyorMu(l);
            if (caliyor && !caldiGoruldu) { caldiGoruldu = true; durum = "çalıyor"; caliyorBas = System.currentTimeMillis(); yaz("" + kim + " çalıyor."); bildir("caliyor"); }
            if (kapat != null && baglandiMi(l, caliyor)) {
                baglandiZaman = System.currentTimeMillis(); durum = "bağlandı"; bildir("baglandi");
                yaz("" + kim + " kabul etti (" + (t / 1000) + " sn). WhatsApp görüşmesi kapatılacak.");
                h.postDelayed(() -> kapatBas(c, n), KAPAT_BEKLE);
            }
        }

        // 25 Eki: reddedilince (ya da cevapsız kalınca) WhatsApp "Cancel / Record video note / Call again" ekranı bırakır:
        // kesin "kabul etmedi" işareti. Cancel'a basıp WhatsApp'ı temiz bırak.
        if (baglandiZaman == 0) {
            AccessibilityNodeInfo iptal = bulIdSonu(l, "cancel_call_back_btn");
            if (iptal != null || bulIdSonu(l, "call_back_btn") != null) {
                String y = Bildirim.sonYazi;
                yaz(kim + " kabul etmedi (reddetti ya da açmadı).");
                if (iptal != null) { boolean ok = iptal.performAction(AccessibilityNodeInfo.ACTION_CLICK); Kayit.yaz("Santral: WhatsApp'taki \"Cancel\" seçildi" + (ok ? "." : " (tıklama kabul edilmedi).")); }
                bitir(c, "kabul etmedi");
                return;
            }
        }

        // görüşme ekranı kayboldu: bitti
        if (kapat == null && t > 4000 && (!sohbetYolu || videoBasildi)) {
            if (++yokSay >= 3) {
                if (baglandiZaman > 0) bitir(c, "kapatıldı");
                else {
                    String y = Bildirim.sonYazi;
                    yaz("" + kim + " kabul etmedi (reddetti ya da açmadı). Son bildirim: \"" + y + "\".");
                    bitir(c, "kabul etmedi");
                }
                return;
            }
        } else yokSay = 0;

        if (t > EN_UZUN && baglandiZaman == 0) {
            yaz("" + (EN_UZUN / 1000) + " sn'de açılmadı, arama kapatılıyor (cevap yok).");
            kapatBas(c, n);
            bitir(c, "cevap yok");
            return;
        }
        h.postDelayed(() -> izle(c, n), ARALIK);
    }

    private static void kapatBas(Context c, int n) {
        if (n != no) return;
        Erisim s = Erisim.ben; if (s == null) return;
        AccessibilityNodeInfo kok = s.waKok();
        List<AccessibilityNodeInfo> l = new ArrayList<>();
        if (kok != null) Erisim.topla(kok, l, 0);
        AccessibilityNodeInfo k = bulKapat(l);
        boolean ok = false;
        if (k != null) ok = k.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (!ok) {
            // düğmeler gizlendiyse önce ekrana bir kez dokun, sonra kapat noktasına
            s.dokun(540, 1000);
            h.postDelayed(() -> { Erisim e = Erisim.ben; if (e != null) e.dokun(KAPAT_X, KAPAT_Y); }, 500);
            yaz("\"Leave call\" düğmesi bulunamadı, ekrandan kapat noktasına dokunuldu (" + KAPAT_X + "," + KAPAT_Y + ").");
        } else yaz("WhatsApp görüşmesi \"" + Erisim.ad(k) + "\" ile kapatıldı.");
    }

    private static void bitir(Context c, String sonuc) {
        if (kim == null) return;
        long sure = (System.currentTimeMillis() - bas) / 1000;
        yaz("bitti (" + kim + ", " + sonuc + ", " + sure + " sn" + (baglandiZaman > 0 ? ", bağlantıdan kapanışa " + (System.currentTimeMillis() - baglandiZaman) / 100 / 10.0 + " sn" : "") + ").");
        if (aId != null) {
            String d;
            if ("kapatıldı".equals(sonuc)) d = "kapandi";
            else if ("kabul etmedi".equals(sonuc)) d = (caliyorBas > 0 && System.currentTimeMillis() - caliyorBas < 25000) ? "red" : "cevapyok";
            else if ("cevap yok".equals(sonuc)) d = "cevapyok";
            else if ("iptal".equals(sonuc)) d = "iptal";
            else d = "hata";
            if (!"iptal".equals(d)) durumGonder(aId, d, "hata".equals(d) ? son : "");
            if (notKime != null) { String[] a = sonArama.get(konuAd(notKime)); if (a != null && a[0].equals(aId)) a[1] = d; }
            notKime = null;
            sonucYaz(aId, d);
            aId = null;
        }
        kim = null; durum = ""; no++;
        birak();
        // WhatsApp'ın kapanış ekranı kalmasın: bizim sayfaya dön
        h.postDelayed(() -> Bekci.uyandir(c.getApplicationContext(), null), 1500);
    }

    // ---------- ekranı okuma ----------
    private static String imza(List<AccessibilityNodeInfo> l) {
        StringBuilder b = new StringBuilder();
        for (AccessibilityNodeInfo d : l) {
            String t = Erisim.str(d.getText());
            if (!t.isEmpty() && !t.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) b.append(t).append('|');   // süre sayacı her saniye değişmesin
            String id = Erisim.str(d.getViewIdResourceName());
            if (id.endsWith("end_call_button") || id.contains("accept") || id.contains("decline")) b.append(id).append('|');
        }
        return b.toString();
    }

    private static String kucuk(AccessibilityNodeInfo d) { return (Erisim.str(d.getText()) + " " + Erisim.str(d.getContentDescription())).toLowerCase(Locale.ROOT); }

    static AccessibilityNodeInfo bulKapat(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) if (Erisim.str(d.getViewIdResourceName()).endsWith("end_call_button")) return d;
        for (AccessibilityNodeInfo d : l) {
            String k = kucuk(d);
            if (d.isClickable() && (k.contains("leave call") || k.contains("end call") || k.contains("hang up") || k.contains("aramayı bitir") || k.contains("görüşmeden ayrıl"))) return d;
        }
        return null;
    }

    // Sohbet ekranının üstündeki görüntülü arama simgesi. Önce kimliğiyle, sonra sadece açıklamasıyla
    // (sohbetteki "Video call" yazılı eski arama balonuna basılmasın diye yazıya bakılmaz)
    private static AccessibilityNodeInfo bulVideo(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) if (Erisim.str(d.getViewIdResourceName()).contains("video_call")) return tiklanir(d);
        for (AccessibilityNodeInfo d : l) {
            String cd = Erisim.str(d.getContentDescription()).trim().toLowerCase(Locale.ROOT);
            if (cd.equals("video call") || cd.equals("görüntülü arama") || cd.equals("görüntülü ara")) return tiklanir(d);
        }
        return null;
    }

    // Arama ekranı ya da soru kutusu mu (kayda sadece bunlar dökülür)
    private static boolean aramaEkrani(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) {
            String id = Erisim.str(d.getViewIdResourceName());
            if (id.endsWith("end_call_button") || id.contains("call_screen") || id.endsWith(":id/message") || id.endsWith(":id/button1")) return true;
        }
        return caliyorMu(l);
    }
    private static AccessibilityNodeInfo bulIdSonu(List<AccessibilityNodeInfo> l, String son) {
        for (AccessibilityNodeInfo d : l) if (Erisim.str(d.getViewIdResourceName()).endsWith(":id/" + son)) return d;
        return null;
    }

    // Kutudaki kapatma düğmesi: "Hang up" / "End" / "Kapat"; yoksa olumsuz düğme (button2). "Continue" asla.
    private static AccessibilityNodeInfo bulKutuKapat(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) {
            String t = Erisim.str(d.getText()).trim().toLowerCase(Locale.ROOT);
            if (d.isClickable() && (t.equals("hang up") || t.equals("end call") || t.equals("end") || t.equals("kapat") || t.equals("aramayı bitir"))) return d;
        }
        for (AccessibilityNodeInfo d : l) if (Erisim.str(d.getViewIdResourceName()).endsWith(":id/button2") && d.isClickable()) return d;
        return null;
    }
    private static AccessibilityNodeInfo bulKutuYazisi(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) if (Erisim.str(d.getViewIdResourceName()).endsWith(":id/message") && !Erisim.str(d.getText()).isEmpty()) return d;
        return null;
    }
    private static String kisa(String t) { t = t.replace('\n', ' '); return t.length() > 120 ? t.substring(0, 120) + "…" : t; }

    // Sohbet listesinde (ya da arama sonuçlarında) adı tam tutan satır
    private static AccessibilityNodeInfo bulListeSatiri(List<AccessibilityNodeInfo> l, String ad) {
        String a = Arama.ad(ad);
        for (AccessibilityNodeInfo d : l) {
            String id = Erisim.str(d.getViewIdResourceName());
            if (id.contains("contact_name") && a.equals(Arama.ad(Erisim.str(d.getText())))) return tiklanir(d);
        }
        return null;
    }
    private static AccessibilityNodeInfo bulYaziKutusu(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) if (d.isEditable()) return d;
        return null;
    }
    private static AccessibilityNodeInfo bulAramaCubugu(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) { String id = Erisim.str(d.getViewIdResourceName()); if (id.endsWith("search_bar_inner_layout") || id.endsWith("menuitem_search")) return tiklanir(d); }
        for (AccessibilityNodeInfo d : l) if (kucuk(d).contains("search")) return tiklanir(d);
        return null;
    }
    private static AccessibilityNodeInfo tiklanir(AccessibilityNodeInfo d) {
        AccessibilityNodeInfo k = d;
        for (int i = 0; i < 5 && k != null; i++) { if (k.isClickable()) return k; k = k.getParent(); }
        return d;
    }

    // Sadece bir soru kutusu açıkken: kutunun "tamam/ara" düğmesi (sohbetteki yazılara basılmasın)
    private static AccessibilityNodeInfo bulOnay(List<AccessibilityNodeInfo> l) {
        boolean kutu = false;
        for (AccessibilityNodeInfo d : l) {
            String id = Erisim.str(d.getViewIdResourceName());
            if (id.endsWith(":id/button1") && d.isClickable()) return d;
            if (id.endsWith(":id/buttonPanel") || id.endsWith(":id/alertTitle") || id.endsWith(":id/message")) kutu = true;
        }
        if (!kutu) return null;
        for (AccessibilityNodeInfo d : l) {
            String t = Erisim.str(d.getText()).trim().toLowerCase(Locale.ROOT);
            if (d.isClickable() && (t.equals("call") || t.equals("start") || t.equals("ok") || t.equals("ara") || t.equals("tamam"))) return d;
        }
        return null;
    }

    private static boolean caliyorMu(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) {
            String k = kucuk(d);
            if (k.contains("ringing") || k.contains("calling") || k.contains("çalıyor") || k.contains("aranıyor") || k.contains("connecting") || k.contains("bağlanıyor")) return true;
        }
        return false;
    }

    // Bağlandı: "konuşuyor" işareti, süre sayacı ya da çaldıktan sonra "çalıyor" yazısının kalkması
    private static boolean baglandiMi(List<AccessibilityNodeInfo> l, boolean caliyor) {
        for (AccessibilityNodeInfo d : l) {
            String k = kucuk(d);
            if (k.contains("now speaking")) return true;
            if (Erisim.str(d.getText()).trim().matches("\\d{1,2}:\\d{2}(:\\d{2})?")) return true;
        }
        if (caldiGoruldu && !caliyor) return true;
        String y = Bildirim.sonYazi.toLowerCase(Locale.ROOT);
        return !caliyor && Bildirim.sonZaman > bas && y.contains("ongoing") && caldiGoruldu;
    }

    // ---------- ekran ----------
    @SuppressWarnings("deprecation")
    private static void ekranAc(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            kilit = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "stand:santral");
            kilit.acquire(EN_UZUN + 15000);
        } catch (Exception e) { }
    }
    private static void birak() {
        try { if (kilit != null && kilit.isHeld()) kilit.release(); } catch (Exception e) { }
        kilit = null;
    }
}
