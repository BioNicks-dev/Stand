package com.bionicks.stand;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

// Pixel açılınca (ya da uygulama güncellenince) bekçiyi başlatır; bekçi de sayfayı açar.
public class Acilis extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Kayit.durdu(c)) return;   // elle durdurulduysa kendiliğinden başlamaz
        Kayit.yaz(Intent.ACTION_BOOT_COMPLETED.equals(a)
                ? "Pixel açıldı, uygulama kendiliğinden başladı."
                : "Uygulama güncellendi, yeniden başladı.");
        Bekci.baslat(c);
    }
}
