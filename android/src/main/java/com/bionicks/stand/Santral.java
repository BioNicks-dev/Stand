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

// Uygulama 14 (santral, parça A): Pixel rehberdeki birini WhatsApp'tan GÖRÜNTÜLÜ arar,
// karşı taraf kabul edince 1-2 sn içinde WhatsApp görüşmesini kapatır ("Leave call").
// Şimdilik sadece Pixel sayfasındaki deneme düğmesiyle çalışır; ekranda gördüklerini Kayıt'a yazar
// (çalıyor / bağlandı / reddedildi ekranlarını öğrenmek için). Erişilebilirlik ve Kişiler izni gerekir.
final class Santral {
    private static final Handler h = new Handler(Looper.getMainLooper());
    static final String MIME_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call";
    static final String MIME_SOHBET = "vnd.android.cursor.item/vnd.com.whatsapp.profile";
    private static final int ARALIK = 700, EN_UZUN = 70000, KAPAT_BEKLE = 1200;
    private static final int KAPAT_X = 876, KAPAT_Y = 2189;   // bölüm 24: görüşme ekranında kapat

    private static int no = 0;                 // her yeni arama eskisini geçersiz kılar
    private static String kim = null, durum = "";
    private static long bas = 0, baglandiZaman = 0;
    private static boolean caldiGoruldu = false, onaylandi = false;
    private static boolean sohbetYolu = false, videoBasildi = false;   // uygulama 16: yedek yol (sohbeti aç, görüntülü arama simgesine dokun)
    private static int dokum = 0, yokSay = 0;
    private static String sonImza = "";
    private static PowerManager.WakeLock kilit = null;

    static boolean rehberIzni(Context c) { return c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED; }

    // Pixel sayfası düğmenin altında gösterir: süren aramanın durumu ya da son 30 sn'deki sonuç
    private static String son = ""; private static long sonZaman = 0;
    private static void yaz(String m) { Kayit.yaz("Santral: " + m); son = m; sonZaman = System.currentTimeMillis(); }
    static String durum() {
        if (kim != null) return kim + ": " + durum;
        return System.currentTimeMillis() - sonZaman < 30000 ? son : "";
    }

    // ---------- başlat ----------
    static void ara(final Context c, final String ad) {
        h.post(() -> {
            if (kim != null) { yaz("zaten bir arama sürüyor (" + kim + ")."); return; }
            if (ad == null || ad.trim().isEmpty()) { yaz("aranacak ad boş."); return; }
            if (!rehberIzni(c)) { yaz("Kişiler izni yok. Pixel sayfasındaki \"Kişiler izni ver\" düğmesine dokunun."); return; }
            if (Erisim.ben == null) { yaz("erişilebilirlik kapalı; arama kapatılamaz, başlatılmadı."); return; }
            long[] veri = bul(c, ad.trim());
            if (veri == null) return;
            ekranAc(c);
            sohbetYolu = false; videoBasildi = false;
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
                if (sohbet < 0) { yaz("WhatsApp sohbeti de bulunamadı, arama başlatılamadı."); birak(); return; }
                Intent s2 = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.withAppendedPath(ContactsContract.Data.CONTENT_URI, String.valueOf(sohbet)), MIME_SOHBET)
                        .setPackage(Erisim.WA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.getApplicationContext().startActivity(s2); sohbetYolu = true; Kayit.yaz("Santral: yedek yol: WhatsApp sohbeti açıldı, görüntülü arama simgesine dokunulacak."); }
                catch (Exception e2) { yaz("WhatsApp sohbeti de açılamadı: " + e2.getClass().getSimpleName() + " · " + kisaHata(e2)); birak(); return; }
            }
            kim = ad.trim(); durum = "başlatılıyor"; bas = System.currentTimeMillis(); baglandiZaman = 0;
            caldiGoruldu = false; onaylandi = false; dokum = 0; yokSay = 0; sonImza = "";
            final int n = ++no;
            yaz("" + kim + " WhatsApp'tan görüntülü aranıyor.");
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
        yaz("rehberde WhatsApp'ı olan \"" + ad + "\" bulunamadı. WhatsApp'ı olan kişiler: " + (adlar.isEmpty() ? "hiç yok" : String.join(", ", adlar)) + ".");
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

        // ekran değiştiyse kayda dök (öğrenmek için; en fazla 10 kez)
        String imza = imza(l);
        // (sohbet ekranı kayda dökülmez: mesajlar yazılmasın)
        if ((!sohbetYolu || videoBasildi) && !imza.equals(sonImza) && dokum < 10) { sonImza = imza; dokum++; s.dok("Santral " + (t / 1000) + " sn", kok); }

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
        if (kapat == null && !onaylandi) {
            AccessibilityNodeInfo onay = bulOnay(l);
            if (onay != null) {
                onaylandi = true;
                boolean ok = onay.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                yaz("WhatsApp onay istedi, \"" + Erisim.ad(onay) + "\" seçildi" + (ok ? "." : " (tıklama kabul edilmedi)."));
            }
        }

        if (baglandiZaman == 0) {
            boolean caliyor = caliyorMu(l);
            if (caliyor && !caldiGoruldu) { caldiGoruldu = true; durum = "çalıyor"; yaz("" + kim + " çalıyor."); }
            if (kapat != null && baglandiMi(l, caliyor)) {
                baglandiZaman = System.currentTimeMillis(); durum = "bağlandı";
                yaz("" + kim + " kabul etti (" + (t / 1000) + " sn). WhatsApp görüşmesi kapatılacak.");
                h.postDelayed(() -> kapatBas(c, n), KAPAT_BEKLE);
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

    // Sohbet ekranının üstündeki görüntülü arama simgesi
    private static AccessibilityNodeInfo bulVideo(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) {
            String id = Erisim.str(d.getViewIdResourceName()), k = kucuk(d);
            if (id.contains("video_call") || k.trim().equals("video call") || k.contains("görüntülü arama") || k.contains("görüntülü ara")) return d.isClickable() ? d : tiklanir(d);
        }
        return null;
    }
    private static AccessibilityNodeInfo tiklanir(AccessibilityNodeInfo d) {
        AccessibilityNodeInfo k = d;
        for (int i = 0; i < 5 && k != null; i++) { if (k.isClickable()) return k; k = k.getParent(); }
        return d;
    }

    private static AccessibilityNodeInfo bulOnay(List<AccessibilityNodeInfo> l) {
        for (AccessibilityNodeInfo d : l) {
            String id = Erisim.str(d.getViewIdResourceName());
            String t = Erisim.str(d.getText()).trim().toLowerCase(Locale.ROOT);
            if (!d.isClickable()) continue;
            if (id.endsWith("android:id/button1")) return d;
            if (t.equals("call") || t.equals("video call") || t.equals("start") || t.equals("ara") || t.equals("görüntülü ara")) return d;
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
