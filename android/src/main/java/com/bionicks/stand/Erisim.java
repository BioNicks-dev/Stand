package com.bionicks.stand;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Uygulama 11: Erişilebilirlik hizmeti. WhatsApp'ın gelen arama ekranında parmak gibi SÜRÜKLER / DOKUNUR.
// (23 Eki: bildirimdeki "Video" düğmesine arka plandan basmak aramayı açmadı; MacroDroid'in çalışan yolu bu.)
//  - Ekran kapalıyken gelen arama → WhatsApp tam ekran arama sayfası → "Accept" parçasına erişilebilirlik tıklaması
//    (24 Eki: tuttu); tutmazsa MacroDroid'in sürüklemesi 546,2065 → 990,2065, 450 ms.
//  - Ekran açıkken gelen arama → üstteki küçük kutu → "Video" düğmesine dokunma (bulunamazsa 773,397).
//  - Ne gördüğünü Kayıt > Pixel'e yazar (ilk denemede tutmazsa düzeltmek için; aşama 2 için görüşme ekranı da yazılır).
// Kendiliğinden hiçbir şey yapmaz: sadece Arama.java "kabul et" deyince çalışır. Bildirim silmez.
public class Erisim extends AccessibilityService {
    static final String WA = "com.whatsapp";
    static volatile Erisim ben = null;
    private static final Handler h = new Handler(Looper.getMainLooper());

    // MacroDroid'de çalışan koordinatlar (Pixel 6a, 1080x2400; bölüm 24)
    private static final int SX1 = 546, SY = 2065, SX2 = 990, SURE = 450, KUTU_X = 773, KUTU_Y = 397;
    private static final int ILK_BEKLE = 2500, ARA = 1800, DENEME = 5;

    private static String isKey = null;     // şu an kabul edilmeye çalışılan arama
    private static int isNo = 0;            // her yeni iş eski işi geçersiz kılar

    @Override protected void onServiceConnected() { ben = this; Kayit.yaz("Erişilebilirlik açık: WhatsApp araması ekrandan kabul edilebilir."); }
    @Override public boolean onUnbind(android.content.Intent i) { ben = null; Kayit.yaz("Erişilebilirlik kapandı (izin alındı ya da Android kapattı)."); return super.onUnbind(i); }
    @Override public void onDestroy() { ben = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { }

    // Ayarlarda açık mı (hizmet o an bağlı olmasa da)
    static boolean izinVar(Context c) {
        try {
            String l = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (l == null) return false;
            ComponentName ad = new ComponentName(c, Erisim.class);
            for (String s : l.split(":")) {
                ComponentName k = ComponentName.unflattenFromString(s);
                if (ad.equals(k)) return true;
            }
        } catch (Exception e) { }
        return false;
    }

    // ---------- Arama.java buradan çağırır ----------
    // true: iş başladı. false: hizmet kapalı (Arama eski yolu dener).
    static boolean kabul(final String key, final String kim) {
        if (ben == null) {
            Kayit.yaz("WhatsApp: erişilebilirlik kapalı, arama ekrandan kabul edilemiyor. Pixel sayfasında \"Erişilebilirlik izni ver\" düğmesine dokunun.");
            return false;
        }
        isKey = key; final int no = ++isNo;
        Kayit.yaz("WhatsApp: " + kim + " araması ekrandan kabul edilecek (" + (ILK_BEKLE / 1000.0) + " sn bekleniyor, WhatsApp ekranını açsın).");
        h.postDelayed(() -> dene(key, kim, no, 1), ILK_BEKLE);
        return true;
    }

    static void birak() { isKey = null; isNo++; }

    // WhatsApp'ın bir penceresi ekranda mı (görüşme sürerken arama ekranı)
    static boolean waEkraniVar() {
        Erisim s = ben;
        if (s == null) return false;
        try { for (AccessibilityWindowInfo w : s.getWindows()) { AccessibilityNodeInfo r = w.getRoot(); if (r != null && WA.equals(str(r.getPackageName()))) return true; } }
        catch (Exception e) { }
        return false;
    }

    private static void dene(final String key, final String kim, final int no, final int n) {
        if (no != isNo || !key.equals(isKey)) return;
        Erisim s = ben;
        if (s == null) { Kayit.yaz("WhatsApp: erişilebilirlik kapandı, kabul yarıda kaldı."); return; }
        if (!Bildirim.gelenVar(key)) {
            if (n == 1) Kayit.yaz("WhatsApp: gelen arama bildirimi kalktı (arama bitti ya da elle açıldı), dokunulmadı.");
            else if (Bildirim.aramaBildirimiVar() || waEkraniVar()) { Kayit.yaz("WhatsApp: arama kabul edildi (" + (n - 1) + ". denemede)."); Arama.kabulEdildi(key); h.postDelayed(() -> s.dok("Görüşme ekranı"), 3000); }
            else Kayit.yaz("WhatsApp: arama bitti ama görüşme görünmüyor (karşı taraf kapatmış ya da kabul tutmamış olabilir).");
            isKey = null;
            return;
        }
        if (n > DENEME) {
            Kayit.yaz("WhatsApp: " + DENEME + " denemede kabul edilemedi. Yukarıdaki ekran satırlarını Claude'a gönderin.");
            isKey = null;
            return;
        }
        AccessibilityNodeInfo wa = s.waKok();
        if (wa != null) {
            if (n == 1) s.dok("Arama ekranı (WhatsApp)", wa);
            AccessibilityNodeInfo parca = s.kabulParcasi(wa);
            if (n % 2 == 1 && parca != null) {
                // 24 Eki kaydı: tam ekranda ayrı "Accept" parçası var; ekran okuyucu tıklaması aramayı açtı
                boolean ok = parca.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Kayit.yaz("WhatsApp: deneme " + n + ": \"" + ad(parca) + "\" parçasına erişilebilirlik tıklaması " + (ok ? "gönderildi." : "kabul edilmedi."));
            } else {
                // yedek: MacroDroid'in sürüklemesi, ortadan sağa
                boolean ok = s.surukle(SX1, SY, SX2, SY, SURE);
                Kayit.yaz("WhatsApp: deneme " + n + ": sürükleme " + SX1 + "," + SY + " → " + SX2 + "," + SY + " (" + SURE + " ms)" + (ok ? "." : " gönderilemedi."));
            }
        } else {
            // WhatsApp tam ekranda değil: ekran açıkken gelen arama üstte küçük kutu olarak çıkar
            AccessibilityNodeInfo dug = s.kutuDugmesi();
            if (dug != null) {
                Rect b = new Rect(); dug.getBoundsInScreen(b);
                if (n == 1) s.dok("Üst kutu", s.kokOf(dug));
                boolean ok = s.dokun(b.centerX(), b.centerY());
                Kayit.yaz("WhatsApp: deneme " + n + ": üst kutudaki \"" + ad(dug) + "\" düğmesine dokunma " + b.centerX() + "," + b.centerY() + (ok ? "." : " gönderilemedi."));
            } else if (s.kutuVar()) {
                boolean ok = s.dokun(KUTU_X, KUTU_Y);
                Kayit.yaz("WhatsApp: deneme " + n + ": üst kutu görüldü, düğme bulunamadı; MacroDroid noktasına dokunma " + KUTU_X + "," + KUTU_Y + (ok ? "." : " gönderilemedi."));
            } else {
                Kayit.yaz("WhatsApp: deneme " + n + ": arama ekranı henüz görünmüyor, bekleniyor.");
                if (n == 2) s.dok("Ekrandaki pencereler", null);
            }
        }
        h.postDelayed(() -> dene(key, kim, no, n + 1), ARA);
    }

    // ---------- ekranı okuma ----------
    private AccessibilityNodeInfo waKok() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && WA.equals(str(r.getPackageName())) && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) return r;
            }
        } catch (Exception e) { }
        return null;
    }

    private static boolean kabulMu(String t) {
        String k = t.toLowerCase(Locale.ROOT);
        if (k.contains("decline") || k.contains("reddet") || k.contains("reply") || k.contains("mesaj") || k.contains("message")) return false;
        return k.contains("accept") || k.contains("answer") || k.contains("kabul") || k.contains("cevapla") || k.contains("yanıtla");
    }

    // Tam ekran arama sayfasında sürüklenecek (kabul) parça
    private AccessibilityNodeInfo kabulParcasi(AccessibilityNodeInfo kok) {
        List<AccessibilityNodeInfo> l = new ArrayList<>(); topla(kok, l, 0);
        for (AccessibilityNodeInfo d : l) { String id = str(d.getViewIdResourceName()).toLowerCase(Locale.ROOT); if (id.contains("accept") || id.contains("answer")) return d; }
        for (AccessibilityNodeInfo d : l) if (kabulMu(str(d.getContentDescription())) || kabulMu(str(d.getText()))) return d;
        return null;
    }

    // Üst kutudaki yeşil "Video" (ya da Answer/Kabul) düğmesi; WhatsApp dışındaki pencerelerde (sistemin kutusu)
    private AccessibilityNodeInfo kutuDugmesi() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || !kutuPenceresi(r)) continue;
                List<AccessibilityNodeInfo> l = new ArrayList<>(); topla(r, l, 0);
                for (AccessibilityNodeInfo d : l) {
                    String t = ad(d).trim().toLowerCase(Locale.ROOT);
                    if (t.equals("video") || t.equals("görüntülü") || (kabulMu(t) && t.length() < 20)) return d;
                }
            }
        } catch (Exception e) { }
        return null;
    }
    private boolean kutuVar() {
        try { for (AccessibilityWindowInfo w : getWindows()) { AccessibilityNodeInfo r = w.getRoot(); if (r != null && kutuPenceresi(r)) return true; } }
        catch (Exception e) { }
        return false;
    }
    // Bu pencerede WhatsApp'ın gelen arama yazısı var mı
    private static boolean kutuPenceresi(AccessibilityNodeInfo r) {
        List<AccessibilityNodeInfo> l = new ArrayList<>(); topla(r, l, 0);
        for (AccessibilityNodeInfo d : l) {
            String t = ad(d).toLowerCase(Locale.ROOT);
            if (t.contains("incoming video call") || t.contains("gelen görüntülü")) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo kokOf(AccessibilityNodeInfo d) {
        AccessibilityNodeInfo k = d;
        for (int i = 0; i < 40; i++) { AccessibilityNodeInfo p = k.getParent(); if (p == null) break; k = p; }
        return k;
    }

    private static void topla(AccessibilityNodeInfo d, List<AccessibilityNodeInfo> l, int derin) {
        if (d == null || derin > 30 || l.size() > 400) return;
        l.add(d);
        for (int i = 0; i < d.getChildCount(); i++) topla(d.getChild(i), l, derin + 1);
    }

    private static String str(CharSequence c) { return c == null ? "" : c.toString(); }
    private static String ad(AccessibilityNodeInfo d) { String t = str(d.getText()); return t.isEmpty() ? str(d.getContentDescription()) : t; }

    // Kayda ekran dökümü: yazısı, açıklaması ya da kimliği olan parçalar (en fazla 30)
    private void dok(String baslik) { AccessibilityNodeInfo r = waKok(); if (r != null) dok(baslik + " (WhatsApp)", r); else dok(baslik + ": WhatsApp ekranda değil", null); }
    private void dok(String baslik, AccessibilityNodeInfo kok) {
        StringBuilder s = new StringBuilder("Ekran · ").append(baslik).append(":");
        try {
            if (kok == null) {
                for (AccessibilityWindowInfo w : getWindows()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    s.append(" [pencere ").append(w.getType()).append(" ").append(r == null ? "?" : str(r.getPackageName())).append("]");
                }
                Kayit.yaz(s.toString());
                return;
            }
            List<AccessibilityNodeInfo> l = new ArrayList<>(); topla(kok, l, 0);
            int say = 0;
            for (AccessibilityNodeInfo d : l) {
                String t = str(d.getText()), cd = str(d.getContentDescription()), id = str(d.getViewIdResourceName());
                if (t.isEmpty() && cd.isEmpty() && id.isEmpty()) continue;
                if (++say > 30) { s.append(" …"); break; }
                if (id.contains(":id/")) id = id.substring(id.indexOf(":id/") + 4);
                Rect b = new Rect(); d.getBoundsInScreen(b);
                s.append(" | ");
                if (!t.isEmpty()) s.append('"').append(kisa(t)).append('"');
                if (!cd.isEmpty() && !cd.equals(t)) s.append(" (").append(kisa(cd)).append(')');
                if (!id.isEmpty()) s.append(" #").append(id);
                if (d.isClickable()) s.append(" ·dokunulur");
                s.append(" @").append(b.centerX()).append(',').append(b.centerY());
            }
        } catch (Exception e) { s.append(" okunamadı (").append(e.getClass().getSimpleName()).append(")"); }
        Kayit.yaz(s.toString());
    }
    private static String kisa(String t) { t = t.replace('\n', ' '); return t.length() > 40 ? t.substring(0, 40) + "…" : t; }

    // ---------- parmak hareketleri ----------
    private int genislik() { try { return getResources().getDisplayMetrics().widthPixels; } catch (Exception e) { return 1080; } }

    private boolean surukle(int x1, int y1, int x2, int y2, int ms) {
        Path p = new Path(); p.moveTo(x1, y1); p.lineTo(x2, y2);
        return hareket(p, ms);
    }
    private boolean dokun(int x, int y) {
        Path p = new Path(); p.moveTo(x, y);
        return hareket(p, 60);
    }
    private boolean hareket(Path p, int ms) {
        try {
            GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) { Kayit.yaz("Erişilebilirlik: hareket yapılamadı (" + e.getClass().getSimpleName() + ")."); return false; }
    }
}
