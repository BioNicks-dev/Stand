package com.bionicks.stand;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import org.json.JSONArray;

// Uygulamanın kendi kayıt satırları. Sayfa 3 sn'de bir alır, Kayıt > Pixel sekmesine düşer.
// "Durduruldu" bilgisi de burada saklanır.
final class Kayit {
    private static final ArrayList<String> SATIRLAR = new ArrayList<>();
    private static final ArrayList<String> KOPYA = new ArrayList<>();

    static synchronized void yaz(String m) {
        String s = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + m;
        SATIRLAR.add(s);
        while (SATIRLAR.size() > 100) SATIRLAR.remove(0);
        KOPYA.add(s);                                   // 27 Kas: sayfa alınca silinmeyen kopya (bekçi uzaktan okutur)
        while (KOPYA.size() > 120) KOPYA.remove(0);
    }

    // 27 Kas: son satırlar; sayfa uyurken bile bekçi bunları sunucuya verir ("bkay" isteği)
    static synchronized JSONArray son(int n) {
        JSONArray a = new JSONArray();
        for (int i = Math.max(0, KOPYA.size() - n); i < KOPYA.size(); i++) a.put(KOPYA.get(i));
        return a;
    }

    static synchronized String al() {
        JSONArray a = new JSONArray();
        for (String s : SATIRLAR) a.put(s);
        SATIRLAR.clear();
        return a.toString();
    }

    private static SharedPreferences ayar(Context c) {
        return c.getApplicationContext().getSharedPreferences("stand", Context.MODE_PRIVATE);
    }
    static boolean durdu(Context c) { return ayar(c).getBoolean("durdu", false); }
    static void durdu(Context c, boolean d) { ayar(c).edit().putBoolean("durdu", d).apply(); }
}
