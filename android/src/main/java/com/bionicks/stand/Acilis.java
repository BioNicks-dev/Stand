package com.bionicks.stand;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Pixel açılınca (ya da uygulama güncellenince) bekçiyi başlatır; bekçi de sayfayı açar.
// Uygulama 13: kendi güncellememizden sonra sayfayı ekrana da getirir.
public class Acilis extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Kayit.durdu(c)) return;   // elle durdurulduysa kendiliğinden başlamaz
        Kayit.yaz(Intent.ACTION_BOOT_COMPLETED.equals(a)
                ? "Pixel açıldı, uygulama kendiliğinden başladı."
                : "Uygulama güncellendi, yeniden başladı.");
        Bekci.baslat(c);
        // Uygulama 13: kendi güncellememizden sonra ekranı açıp sayfayı öne getir ("✓ Güncellendi" görünsün)
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) && Guncelle.yeniKuruldu(c)) {
            final PendingResult r = goAsync();
            final Context uc = c.getApplicationContext();
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try { Bekci.uyandir(uc, "Güncelleme bitti"); } finally { r.finish(); }
            }, 1500);
        }
    }
}
