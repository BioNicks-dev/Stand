#!/bin/bash
# STAND YÜKLE (Claude'un kullandığı hâli)
# Ne yapar: değişen sayfalara sürüm damgası basar, değişiklikleri GitHub'a gönderir.
# Kullanım:  bash araclar/yukle.sh "ne değişti, kısaca"
set -e
cd "$(dirname "$0")/.."                       # depo klasörüne geç

# 0) Başka yerden (Stand Yükle, başka sohbet) yeni yükleme yapıldıysa DUR, üstüne yazma
git fetch -q --depth=1 origin main
if [ "$(git rev-parse HEAD)" != "$(git rev-parse origin/main)" ]; then
  echo "DUR: depoda bu klasörde olmayan yeni bir yükleme var. Önce onu almak lazım."; exit 1
fi

ACIKLAMA="$1"
if [ -z "$ACIKLAMA" ]; then echo "HATA: açıklama yazılmadı"; exit 1; fi

# 1) Şu anki saat, Florida saatiyle, "27 Kas 01:10" biçiminde
AYLAR=(Oca Şub Mar Nis May Haz Tem Ağu Eyl Eki Kas Ara)
AY=${AYLAR[$((10#$(TZ=America/New_York date +%m)-1))]}
DAMGA="$((10#$(TZ=America/New_York date +%d))) $AY $(TZ=America/New_York date +%H:%M)"

# 2) index.html değiştiyse üç yerdeki damgayı yenile
if ! git diff --quiet HEAD -- index.html; then
  sed -i -E "s/SAYFA_SURUM=\"[0-9]+ [^\"]+ [0-9]{2}:[0-9]{2}\"/SAYFA_SURUM=\"$DAMGA\"/" index.html
  sed -i -E "s/(stand-surum\" content=\")[0-9]+ [^\"]+ [0-9]{2}:[0-9]{2}\"/\1$DAMGA\"/" index.html
  sed -i -E "s/Sayfa: [0-9]+ [^ <]+ [0-9]{2}:[0-9]{2}/Sayfa: $DAMGA/" index.html
  echo "index.html damgası: $DAMGA"
fi

# 3) kamera.html değiştiyse damgasını yenile
if ! git diff --quiet HEAD -- kamera.html; then
  sed -i -E "s/var SURUM=\"[0-9]+ [^\"]+ [0-9]{2}:[0-9]{2}\"/var SURUM=\"$DAMGA\"/" kamera.html
  echo "kamera.html damgası: $DAMGA"
fi

# 4) Değişen dosyaları listele, kaydet, gönder
git add -A
if git diff --cached --quiet; then echo "Değişen dosya yok, gönderilmedi."; exit 0; fi
echo "Gönderilenler:"; git diff --cached --name-only | sed 's/^/  - /'
git commit -q -m "$ACIKLAMA, $DAMGA"
git -c push.negotiate=true push -q origin main
echo "TAMAM: GitHub'a gönderildi."
# Uygulama dosyası (android/) değiştiyse: 1) bash araclar/derleme.sh  2) Chrome: araclar/pixel_guncelle.js  3) 1 dk sonra kayit_oku.js ile sürümü gör
