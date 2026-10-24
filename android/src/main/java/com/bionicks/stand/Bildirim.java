package com.bionicks.stand;

import android.app.Notification;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Uygulama 7: WhatsApp'ın ARAMA bildirimlerini okur (mesaj bildirimlerine bakmaz, kayda yazmaz).
//  - Gelen arama (bildirimde "Answer/Cevapla" düğmesi var) → Arama.gelen karar verir.
//  - Süren görüşme ("Hang up/Kapat" var, cevapla yok) → Arama.suruyor.
//  - Bildirimin yazısı ve düğmeleri Kayıt > Pixel'e yazılır (aşama 2 için: bağlanınca ne yazıyor, hangi düğmeler var).
// ASLA bildirim silmez (cancelNotification yok): 8-9 Ekim'de bildirim silmek WhatsApp'ın arama ekranını bozmuştu.
public class Bildirim extends NotificationListenerService {
    static final String WA = "com.whatsapp";
    static volatile boolean bagli = false;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, Long> yazilan = new HashMap<>();

    @Override public void onListenerConnected() { bagli = true; Kayit.yaz("Bildirim erişimi açık: WhatsApp aramaları izleniyor."); }
    @Override public void onListenerDisconnected() { bagli = false; Kayit.yaz("Bildirim erişimi koptu (Android kapattı ya da izin alındı)."); }

    private static String m(CharSequence c) { return c == null ? "" : c.toString(); }

    private static boolean icerir(String t, String... l) {
        String k = t.toLowerCase(Locale.ROOT);
        for (String x : l) if (k.contains(x)) return true;
        return false;
    }
    // 23 Eki kaydı: WhatsApp'ın gelen görüntülü arama bildiriminde düğmeler "Decline, Video"; kabul düğmesinin adı "Video"
    private static boolean cevaplaMi(String t) {
        String k = t.trim().toLowerCase(Locale.ROOT);
        return icerir(t, "answer", "cevapla", "yanıtla", "kabul") || k.equals("video") || k.equals("voice") || k.equals("görüntülü") || k.equals("sesli");
    }
    private static boolean gelenMi(String yazi) { return icerir(yazi, "incoming", "gelen"); }
    private static boolean reddetMi(String t) { return icerir(t, "decline", "reddet"); }
    private static boolean kapatMi(String t) { return icerir(t, "hang up", "end call", "kapat", "sonlandır", "bitir"); }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !WA.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        PendingIntent ac = null, red = null; boolean kapat = false;
        StringBuilder dug = new StringBuilder();
        if (n.actions != null) for (Notification.Action a : n.actions) {
            String t = m(a.title);
            if (dug.length() > 0) dug.append(", ");
            dug.append(t);
            if (cevaplaMi(t)) ac = a.actionIntent;
            else if (reddetMi(t)) red = a.actionIntent;
            else if (kapatMi(t)) kapat = true;
        }
        boolean arama = Notification.CATEGORY_CALL.equals(n.category) || ac != null || kapat;
        if (!arama) return;   // mesaj bildirimi: hiç bakılmaz
        Bundle e = n.extras;
        String baslik = e == null ? "" : m(e.getCharSequence(Notification.EXTRA_TITLE));
        String yazi = e == null ? "" : m(e.getCharSequence(Notification.EXTRA_TEXT));
        // yedek: "Incoming …" yazan bildirimde reddet/kapat olmayan ilk düğme kabul düğmesidir
        if (ac == null && gelenMi(yazi) && n.actions != null) for (Notification.Action a : n.actions) {
            String t = m(a.title);
            if (!reddetMi(t) && !kapatMi(t)) { ac = a.actionIntent; break; }
        }
        String ozet = "WhatsApp bildirimi: \"" + baslik + "\" · \"" + yazi + "\" · düğmeler: " + (dug.length() > 0 ? dug : "yok")
                + ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0 ? " · sürekli" : "");
        long simdi = System.currentTimeMillis();
        Long once = yazilan.get(ozet);
        if (once == null || simdi - once > 20000) { yazilan.put(ozet, simdi); Kayit.yaz(ozet); }
        if (yazilan.size() > 40) yazilan.clear();
        if (ac != null) Arama.gelen(this, sbn.getKey(), baslik, yazi, ac, red);
        else if (kapat) Arama.suruyor(sbn.getKey(), baslik);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null || !WA.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        boolean arama = n != null && (Notification.CATEGORY_CALL.equals(n.category) || (n.actions != null && n.actions.length > 0 && (n.flags & Notification.FLAG_ONGOING_EVENT) != 0));
        if (!arama) return;
        final String key = sbn.getKey();
        // gelen arama bildirimi kalkıp yerine görüşme bildirimi gelebilir: 3 sn sonra bak
        h.postDelayed(() -> Arama.kalkti(key, baskaAramaVar(key)), 3000);
    }

    private boolean baskaAramaVar(String haric) {
        try {
            StatusBarNotification[] l = getActiveNotifications();
            if (l != null) for (StatusBarNotification s : l) {
                if (!WA.equals(s.getPackageName()) || s.getKey().equals(haric)) continue;
                Notification n = s.getNotification();
                if (n != null && Notification.CATEGORY_CALL.equals(n.category)) return true;
            }
        } catch (Exception e) { }
        return false;
    }
}
