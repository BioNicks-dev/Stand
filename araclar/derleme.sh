#!/bin/bash
# DERLEME KONTROLÜ
# Ne yapar: Pixel uygulamasının GitHub'daki derlemesini bekler, sonucu tek satırla söyler.
# Kullanım:  bash araclar/derleme.sh
DEPO=BioNicks-dev/Stand
for i in $(seq 1 40); do                      # en çok ~10 dakika bekler (40 x 15 sn)
  SATIR=$(gh api "repos/$DEPO/actions/workflows/apk.yml/runs?per_page=1" \
    --jq '.workflow_runs[0] | "\(.run_number) \(.status) \(.conclusion) \(.id) \(.head_sha)"')
  read NO DURUM SONUC KIMLIK SHA <<< "$SATIR"
  # Yeni yüklemenin derlemesi henüz başlamadıysa eski sonucu söyleme, bekle
  if [ "$SHA" != "$(git rev-parse HEAD)" ]; then sleep 15; continue; fi
  if [ "$DURUM" = "completed" ]; then
    if [ "$SONUC" = "success" ]; then echo "v$NO hazır. Pixel yarım dakikada kurar."
    else
      echo "v$NO derleme BOZUK. Hata satırları:"
      gh run view "$KIMLIK" -R "$DEPO" --log-failed 2>/dev/null | grep -i -E "error|hata" | head -8
    fi
    exit 0
  fi
  sleep 15
done
echo "Derleme 10 dakikada bitmedi, GitHub'da takılmış olabilir."
