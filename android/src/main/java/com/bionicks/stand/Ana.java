package com.bionicks.stand;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.util.ArrayList;

// Ekran: bekçinin içinde yaşayan sayfayı gösterir. Bu ekran kapansa da sayfa kapanmaz.
public class Ana extends Activity {
    static volatile Ana ben;
    volatile boolean onde = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ben = this;
        Kayit.durdu(this, false);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        tamEkran();
        Bekci.baslat(this);
        Sayfa.tak(this);
        izinleriIste();
        if (Build.VERSION.SDK_INT >= 33)
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> moveTaskToBack(true));
        kilidiKaldir(getIntent());
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); kilidiKaldir(i); }
    @Override protected void onResume() { super.onResume(); onde = true; tamEkran(); }
    @Override protected void onPause() { onde = false; super.onPause(); }

    // Bekçi uyandırdıysa kilit ekranını da kaldır (şifresiz kilitte kendiliğinden kalkar)
    private void kilidiKaldir(Intent i) {
        if (i == null || !i.getBooleanExtra("uyan", false)) return;
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override public void onDismissError() { Kayit.yaz("Kilit ekranı kaldırılamadı."); }
                @Override public void onDismissCancelled() { Kayit.yaz("Kilit ekranı kaldırılmadı (şifreli kilit olabilir)."); }
            });
        }
    }

    private void tamEkran() {
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) tamEkran(); }

    private void izinleriIste() {
        ArrayList<String> l = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) l.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) l.add(Manifest.permission.RECORD_AUDIO);
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) l.add(Manifest.permission.READ_CONTACTS);   // uygulama 14: santral
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!l.isEmpty()) requestPermissions(l.toArray(new String[0]), 7);
    }

    // Geri tuşu sayfayı kapatmasın
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { moveTaskToBack(true); }

    @Override
    protected void onDestroy() {
        Sayfa.birak(this);
        if (ben == this) ben = null;
        super.onDestroy();
    }
}
