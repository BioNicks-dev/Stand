// =====================================================================
//  INTERNET KARTI (ESP32-S3)
//  Wi-Fi ve kisiye ozel sunucuya (EMQX Cloud) baglanir, komutlari
//  kabloyla Bluetooth kartina iletir. Bluetooth KULLANMAZ.
//  Baglanti:
//    bu kartin GPIO17 (TX)  -> Bluetooth kartinin GPIO18 (RX)
//    bu kartin GPIO18 (RX)  <- Bluetooth kartinin GPIO17 (TX)
//    GND <-> GND
//
//  ayarlar.h icinde olmasi gerekenler:
//    WIFI_ADI, WIFI_SIFRE, ODA_KODU, YONETICI_KODU,
//    SUNUCU_KULLANICI, SUNUCU_SIFRE
//
//  4 Ekim 2026:
//   - Bluetooth kartinin yazilari bu kartin Serial'ine [BT] ile yazilir.
//   - Iki kartin son yazilari sayfadaki Kayit bolumune gider (sadece istenince).
//
//  5 Ekim 2026:
//   - Arduino IDE'den dogrudan ag yuklemesi: Tools > Port > "stand at ..." > Upload (sifresiz).
//     Hangi karta ait oldugu programin imzasindan anlasilir; Bluetooth kartininsa kabloyla aktarilir.
//   - (2. surum) Tarayici guncelleme sayfasi kaldirildi; yedek yol USB.
// =====================================================================

#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <PubSubClient.h>
#include <ArduinoJson.h>
#include <ESPmDNS.h>
#include <Update.h>
#include <WiFiUdp.h>
#include <MD5Builder.h>
#include <time.h>
#include "ayarlar.h"

#ifndef RGB_BUILTIN
#define RGB_BUILTIN 48
#endif

// Kart imzasi ve surumu. Kablosuz guncellemede yanlis kartin dosyasi yuklenmesin diye
// dosyada bu imza aranir. Degistirmeyin.
const char IMZA[] = "STANDKARTI:INTERNET|27 Ekim 2026 · 1";
const char* surum() { return strchr(IMZA, '|') + 1; }

const char* SUNUCU = "l08d16ff.ala.us-east-1.emqxsl.com";
const uint16_t SUNUCU_PORT = 8883;
const int HAT_RX = 18, HAT_TX = 17;
const uint32_t HIZ_NORMAL = 115200, HIZ_GUNCELLEME = 460800;
const uint16_t OTA_PORT = 3232;   // Arduino IDE ag yuklemesi
// Kablosuz guncellemede programin ait oldugu kart (en ustte: Arduino'nun otomatik prototipleri icin)
enum Hedef { H_YOK, H_NET, H_BT };

// Durum sunucuya sadece degisince gider; degismezse en gec bu surede bir "buradayim" olarak tekrar gider.
// (Ucretsiz aylik veri kotasini korumak icin.)
const uint32_t DURUM_TEKRAR = 60000;

// Sunucunun gercekten bizimki oldugunu dogrulayan kok sertifika (DigiCert Global Root G2).
// Gizli bir bilgi degildir.
const char* KOK_SERTIFIKA = R"SERTIFIKA(
-----BEGIN CERTIFICATE-----
MIIDjjCCAnagAwIBAgIQAzrx5qcRqaC7KGSxHQn65TANBgkqhkiG9w0BAQsFADBh
MQswCQYDVQQGEwJVUzEVMBMGA1UEChMMRGlnaUNlcnQgSW5jMRkwFwYDVQQLExB3
d3cuZGlnaWNlcnQuY29tMSAwHgYDVQQDExdEaWdpQ2VydCBHbG9iYWwgUm9vdCBH
MjAeFw0xMzA4MDExMjAwMDBaFw0zODAxMTUxMjAwMDBaMGExCzAJBgNVBAYTAlVT
MRUwEwYDVQQKEwxEaWdpQ2VydCBJbmMxGTAXBgNVBAsTEHd3dy5kaWdpY2VydC5j
b20xIDAeBgNVBAMTF0RpZ2lDZXJ0IEdsb2JhbCBSb290IEcyMIIBIjANBgkqhkiG
9w0BAQEFAAOCAQ8AMIIBCgKCAQEAuzfNNNx7a8myaJCtSnX/RrohCgiN9RlUyfuI
2/Ou8jqJkTx65qsGGmvPrC3oXgkkRLpimn7Wo6h+4FR1IAWsULecYxpsMNzaHxmx
1x7e/dfgy5SDN67sH0NO3Xss0r0upS/kqbitOtSZpLYl6ZtrAGCSYP9PIUkY92eQ
q2EGnI/yuum06ZIya7XzV+hdG82MHauVBJVJ8zUtluNJbd134/tJS7SsVQepj5Wz
tCO7TG1F8PapspUwtP1MVYwnSlcUfIKdzXOS0xZKBgyMUNGPHgm+F6HmIcr9g+UQ
vIOlCsRnKPZzFBQ9RnbDhxSJITRNrw9FDKZJobq7nMWxM4MphQIDAQABo0IwQDAP
BgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBhjAdBgNVHQ4EFgQUTiJUIBiV
5uNu5g/6+rkS7QYXjzkwDQYJKoZIhvcNAQELBQADggEBAGBnKJRvDkhj6zHd6mcY
1Yl9PMWLSn/pvtsrF9+wX3N3KjITOYFnQoQj8kVnNeyIv/iPsGEMNKSuIEyExtv4
NeF22d+mQrvHRAiGfzZ0JFrabA0UWTW98kndth/Jsw1HKj2ZL7tcu7XUIOGZX1NG
Fdtom/DzMNU+MeKNhJ7jitralj41E6Vf8PlwUHBHQRFXGU7Aj64GxJUTFy8bJZ91
8rGOmaFvE7FBcf6IKshPECBV1/MUReXgRPTqh5Uykw7+U0b6LJ3/iyK5S9kJRaTe
pLiaWN0bfVKfjllDiIGknibVb63dDcY3fe0Dkhvld1927jyNxF1WW6LZZm6zNTfl
MrY=
-----END CERTIFICATE-----
)SERTIFIKA";

WiFiClientSecure guvenli;
PubSubClient mqtt(guvenli);
String konuKomut, konuVar, konuDurum, konuKayit;

uint32_t sonBluetoothMesaji = 0;   // Bluetooth kartindan son durum mesaji
uint32_t sonIsaret = 0, sonYedekDurum = 0;
uint32_t sunucuBekle = 5000;       // sunucuya yeniden deneme araligi (sifre hatasinda uzar)
int sonSunucuHatasi = 999;         // Serial'e ayni hatayi tekrar tekrar yazmamak icin
bool sunucuBagliydi = false;
bool gGuncelleme = false;          // kablosuz guncelleme suruyor mu
char btSurum[32] = "bilinmiyor";   // Bluetooth kartinin bildirdigi surum
bool mdnsAcik = false;

// Son yayinlanan durum (ayni ise tekrar gonderilmez)
static char sonYayin[2400];
size_t sonYayinUzunluk = 0;
uint32_t sonYayinZamani = 0;
bool yayinZorla = true;

void isik(uint8_t r, uint8_t g, uint8_t b) { rgbLedWrite(RGB_BUILTIN, r, g, b); }

uint16_t crc16(const uint8_t* d, size_t n) {
  uint16_t c = 0;
  for (size_t i = 0; i < n; i++) {
    c ^= d[i];
    for (int k = 0; k < 8; k++) c = (c & 1) ? (c >> 1) ^ 0xA001 : (c >> 1);
  }
  return c;
}

// ---------------------------------------------------------------------
//  KAYIT: iki kartin son 60 satiri burada tutulur. Sayfa (yonetici, Ayarlar acikken)
//  birkac saniyede bir "su numaradan sonrasini ver" der; sadece yeni satirlar gider.
// ---------------------------------------------------------------------
const int KAYIT_SAYI = 60, KAYIT_UZ = 176;
struct KayitSatiri { uint32_t sira; char metin[KAYIT_UZ]; };
KayitSatiri kayitlar[KAYIT_SAYI];
uint32_t kayitSira = 0;     // son satirin numarasi
uint16_t oturum = 0;        // her acilista farkli (sayfa kartin yeniden basladigini anlar)

void saatYaz(char* s, size_t n) {
  time_t t = time(nullptr);
  if (t > 1700000000) { struct tm z; localtime_r(&t, &z); snprintf(s, n, "%02d:%02d:%02d", z.tm_hour, z.tm_min, z.tm_sec); }
  else snprintf(s, n, "+%lus", (unsigned long)(millis() / 1000));
}

void kayitEkle(const char* kaynak, const char* metin) {
  kayitSira++;
  KayitSatiri& k = kayitlar[(kayitSira - 1) % KAYIT_SAYI];
  k.sira = kayitSira;
  char saat[16];
  saatYaz(saat, sizeof(saat));
  snprintf(k.metin, KAYIT_UZ, "%s %s %s", saat, kaynak, metin);
}

// Serial Monitor'a yazar ve her satiri kayda ekler
class KayitYazici : public Print {
  char satir[KAYIT_UZ];
  int n = 0;
 public:
  size_t write(uint8_t c) override {
    Serial.write(c);
    if (c == '\n') { satir[n] = 0; if (n) kayitEkle("NET", satir); n = 0; }
    else if (c != '\r' && n < KAYIT_UZ - 1) satir[n++] = c;
    return 1;
  }
  size_t write(const uint8_t* b, size_t len) override {
    for (size_t i = 0; i < len; i++) write(b[i]);
    return len;
  }
};
KayitYazici Kayit;

void kayitIstegi(JsonDocument& d) {
  if (strcmp(d["yk"] | "", YONETICI_KODU) != 0) return;
  char o[8];
  snprintf(o, sizeof(o), "%04x", oturum);
  uint32_t son = d["son"] | 0;
  if (strcmp(d["o"] | "", o) != 0) son = 0;          // kart yeniden basladiysa bastan
  if (son >= kayitSira) return;                       // yeni satir yok
  uint32_t ilk = son + 1;
  if (kayitSira > (uint32_t)KAYIT_SAYI && ilk < kayitSira - KAYIT_SAYI + 1) ilk = kayitSira - KAYIT_SAYI + 1;
  static char cikti[2100];
  size_t bas = snprintf(cikti, sizeof(cikti), "#%s\n", o), n = bas;
  for (uint32_t s = ilk; s <= kayitSira; s++) {
    char satir[KAYIT_UZ + 16];
    int m = snprintf(satir, sizeof(satir), "%lu %s\n", (unsigned long)s, kayitlar[(s - 1) % KAYIT_SAYI].metin);
    if (n + m >= 2000) { mqtt.publish(konuKayit.c_str(), (const uint8_t*)cikti, n, false); n = bas; }
    memcpy(cikti + n, satir, m);
    n += m;
  }
  if (n > bas) mqtt.publish(konuKayit.c_str(), (const uint8_t*)cikti, n, false);
}

// ---------------------------------------------------------------------
void wifiBaglan() {
  if (WiFi.status() == WL_CONNECTED) return;
  Kayit.printf("Wi-Fi'a baglaniliyor: %s\n", WIFI_ADI);
  static bool ilk = true;
  if (ilk) { WiFi.mode(WIFI_STA); WiFi.setAutoReconnect(true); WiFi.begin(WIFI_ADI, WIFI_SIFRE); ilk = false; }
  else WiFi.reconnect();
  uint32_t bas = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - bas < 15000) { delay(300); Serial.print("."); }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Kayit.printf("Wi-Fi bagli, IP: %s\n", WiFi.localIP().toString().c_str());
    if (!mdnsAcik && MDNS.begin("stand")) {
      MDNS.enableArduino(OTA_PORT, false);   // Arduino IDE'de ag portu olarak gorunsun (sifresiz)
      mdnsAcik = true;
    }
    Kayit.println("Ag yuklemesi hazir: Arduino IDE > Tools > Port > \"stand at ...\"");
  }
  else Kayit.println("Wi-Fi baglanamadi, adi ve sifreyi kontrol edin.");
}

// Sertifika kontrolu icin saat (internetten alinir, birkac saniye surer)
void saatiAyarla() {
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  setenv("TZ", "EST5EDT,M3.2.0,M11.1.0", 1);   // kayitlardaki saat icin (Florida)
  tzset();
  if (WiFi.status() != WL_CONNECTED) return;
  uint32_t bas = millis();
  while (time(nullptr) < 1700000000 && millis() - bas < 6000) delay(200);
  if (time(nullptr) >= 1700000000) Kayit.println("Saat ayarlandi.");
  else Kayit.println("Saat henuz alinamadi, arka planda denenecek.");
}

// Sunucudan gelen mesaji kabloyla Bluetooth kartina ilet (kayit istegi burada cevaplanir)
void sunucudanGeldi(char* konu, byte* veri, unsigned int uzunluk) {
  if (gGuncelleme) return;   // guncellemede kablo mesgul
  String k = konu;
  char tur = 0;
  if (k == konuKomut) tur = 'K';
  else if (k == konuVar) tur = 'V';
  if (!tur) return;
  if (tur == 'K' && uzunluk < 300) {
    char kopya[300];
    memcpy(kopya, veri, uzunluk);
    kopya[uzunluk] = 0;
    if (strstr(kopya, "\"kayit\"")) {
      JsonDocument d;
      if (!deserializeJson(d, kopya) && strcmp(d["t"] | "", "kayit") == 0) { kayitIstegi(d); return; }
    }
  }
  Serial1.write(tur);
  Serial1.write(veri, uzunluk);
  Serial1.write('\n');
}

// Durumu sunucuya gonder: sadece degistiyse ya da DURUM_TEKRAR doldugysa.
// Bluetooth kartinin durumuna bu kartin bilgisi (IP, surum) eklenir.
void durumYayinla(const char* veri, size_t n) {
  if (!mqtt.connected() || n < 2 || veri[n - 1] != '}') return;
  static char tam[2400];
  if (n - 1 >= sizeof(tam) - 120) return;
  memcpy(tam, veri, n - 1);
  int ek = snprintf(tam + n - 1, sizeof(tam) - (n - 1), ",\"net\":{\"ip\":\"%s\",\"surum\":\"%s\"}}",
                    WiFi.localIP().toString().c_str(), surum());
  size_t m = n - 1 + ek;
  uint32_t simdi = millis();
  bool ayni = !yayinZorla && m == sonYayinUzunluk && memcmp(tam, sonYayin, m) == 0;
  if (ayni && simdi - sonYayinZamani < DURUM_TEKRAR) return;
  if (mqtt.publish(konuDurum.c_str(), (const uint8_t*)tam, m, true)) {
    memcpy(sonYayin, tam, m);
    sonYayinUzunluk = m;
    sonYayinZamani = simdi;
    yayinZorla = false;
  }
}

void sunucuyaBaglan() {
  if (mqtt.connected() || WiFi.status() != WL_CONNECTED) return;
  String id = "comitok_net_" + String((uint32_t)ESP.getEfuseMac(), HEX);
  if (sonSunucuHatasi == 999) Kayit.println("Sunucuya baglaniliyor...");
  if (mqtt.connect(id.c_str(), SUNUCU_KULLANICI, SUNUCU_SIFRE, konuDurum.c_str(), 0, true, "{\"cevrimdisi\":true}")) {
    mqtt.subscribe(konuKomut.c_str());
    mqtt.subscribe(konuVar.c_str());
    Kayit.println("Sunucuya baglanildi.");
    sunucuBekle = 5000;
    sonSunucuHatasi = 999;
    sunucuBagliydi = true;
    yayinZorla = true;          // baglanir baglanmaz guncel durum gitsin
  } else {
    int h = mqtt.state();
    if (h == 4 || h == 5) sunucuBekle = 60000;   // sifre hatasinda sik deneme yapma (sunucu engelleyebilir)
    else sunucuBekle = 10000;
    if (h != sonSunucuHatasi) {
      sonSunucuHatasi = h;
      if (h == 4 || h == 5)
        Kayit.println("Sunucu kullanici adini veya sifreyi kabul etmedi. ayarlar.h icindeki SUNUCU_KULLANICI ve SUNUCU_SIFRE'yi kontrol edin. 1 dakikada bir denenecek.");
      else if (h == -2)
        Kayit.println("Sunucuya ulasilamadi (internet veya sertifika). Tekrar denenecek.");
      else
        Kayit.printf("Sunucu hatasi: %d. Tekrar denenecek.\n", h);
    }
  }
}

// Bluetooth kartindan gelen bir satir: D{...} durum, L... yazi
void satirIsle(char* t, size_t n) {
  if (n > 1 && t[0] == 'D') {
    if (!sonBluetoothMesaji) Kayit.println("Bluetooth kartiyla baglanti kuruldu.");
    sonBluetoothMesaji = millis();
    const char* s = strstr(t, "\"surum\":\"");
    if (s) {
      s += 9;
      size_t i = 0;
      while (s[i] && s[i] != '"' && i < sizeof(btSurum) - 1) { btSurum[i] = s[i]; i++; }
      btSurum[i] = 0;
    }
    durumYayinla(t + 1, n - 1);
  } else if (n > 1 && t[0] == 'L') {
    Serial.print("[BT] ");
    Serial.println(t + 1);
    kayitEkle("BT", t + 1);
  }
}

void hatOku() {
  static char tampon[2048];   // Bluetooth kartinin durum mesaji en fazla ~1900
  static size_t uzunluk = 0;
  while (Serial1.available()) {
    char c = Serial1.read();
    if (c == '\n') {
      tampon[uzunluk] = 0;
      satirIsle(tampon, uzunluk);
      uzunluk = 0;
    } else if (c != '\r' && uzunluk < sizeof(tampon) - 1) {
      tampon[uzunluk++] = c;
    }
  }
}

bool bluetoothKartiVar() { return sonBluetoothMesaji && millis() - sonBluetoothMesaji < 15000; }   // Bluetooth karti tarama sirasinda ~10 sn susabilir

// ---------------------------------------------------------------------
//  KABLOSUZ GUNCELLEME
//  Arduino IDE'den Upload (Tools > Port > "stand at ..."): espota protokolu, sifresiz
//  (IDE sifre kutusuna herhangi bir sey yazilir, kullanilmaz). Yedek yol: USB.
//  Gelen program once bu kartin PSRAM'ina alinir; icindeki imzaya bakilarak hangi karta ait
//  oldugu anlasilir. Internet kartininsa kendini gunceller, Bluetooth kartininsa kabloyla
//  parca parca aktarir (cerceve + CRC + onay). Yarida kalirsa iki kartta da eski program calisir.
// ---------------------------------------------------------------------
bool btHizli = false;
uint8_t gSira = 0;
WiFiUDP otaUdp;

struct ImzaTarayici {
  char d[24];
  size_t i = 0;
  bool bulundu = false;
  void sifirla() { i = 0; bulundu = false; }
  void besle(uint8_t c) {
    if (bulundu) return;
    if (c == (uint8_t)d[i]) { if (!d[++i]) bulundu = true; }
    else i = (c == (uint8_t)d[0]) ? 1 : 0;
  }
};
ImzaTarayici imzaNet, imzaBt;

void imzalariHazirla() {
  size_t u = strchr(IMZA, '|') - IMZA;
  memcpy(imzaNet.d, IMZA, u); imzaNet.d[u] = 0;
  // Bluetooth kartinin imzasi bu programda acik yazilmaz (yoksa bu dosyada da bulunurdu)
  static volatile const char KODLU[] = "TUBOELBSUJ;CMVFUPPUI";
  size_t i = 0;
  for (; KODLU[i] && i < sizeof(imzaBt.d) - 1; i++) imzaBt.d[i] = KODLU[i] - 1;
  imzaBt.d[i] = 0;
}

void btHizNormal() {
  if (!btHizli) return;
  Serial1.flush();
  Serial1.updateBaudRate(HIZ_NORMAL);
  btHizli = false;
}

// Bluetooth kartindan "UOK" bekle (arada gelen yazi satirlarini kaybetme)
bool btHazirBekle(uint32_t ms) {
  static char t[2048];
  size_t n = 0;
  uint32_t bas = millis();
  while (millis() - bas < ms) {
    while (Serial1.available()) {
      char c = Serial1.read();
      if (c == '\n') {
        t[n] = 0;
        if (!strcmp(t, "UOK")) return true;
        if (!strcmp(t, "UHATA")) return false;
        if (n > 1 && t[0] == 'L') satirIsle(t, n);
        n = 0;
      } else if (c != '\r' && n < sizeof(t) - 1) t[n++] = c;
    }
    delay(2);
  }
  return false;
}

bool btBasla(size_t boyut) {
  hatOku();
  Serial1.printf("U%u\n", (unsigned)boyut);
  if (!btHazirBekle(5000)) return false;
  Serial1.updateBaudRate(HIZ_GUNCELLEME);
  btHizli = true;
  delay(60);
  while (Serial1.available()) Serial1.read();
  gSira = 0;
  return true;
}

// Bir cerceve gonder, cevap bekle. A: tamam, F: basarisiz, 0: cevap yok
char btCerceve(char tur, const uint8_t* v, uint16_t uz, uint32_t ms) {
  uint8_t bas[4] = { (uint8_t)tur, gSira, (uint8_t)(uz & 0xFF), (uint8_t)(uz >> 8) };
  uint16_t k = crc16(v, uz);
  uint8_t son[2] = { (uint8_t)(k >> 8), (uint8_t)(k & 0xFF) };
  for (int deneme = 0; deneme < 3; deneme++) {
    while (Serial1.available()) Serial1.read();
    Serial1.write(bas, 4);
    if (uz) Serial1.write(v, uz);
    Serial1.write(son, 2);
    uint32_t b = millis();
    char c = 0;
    while (millis() - b < ms) {
      if (Serial1.available()) {
        c = Serial1.read();
        if (c == 'A' || c == 'N' || c == 'F') break;
        c = 0;
      } else delay(1);
    }
    if (c == 'A' || c == 'F') return c;
  }
  return 0;
}

void btIptal() {
  if (!btHizli) return;
  uint8_t x[6] = { 'X', gSira, 0, 0, 0, 0 };
  Serial1.write(x, 6);
  delay(50);
  btHizNormal();
}

// Bellekteki programi dogru karta uygula. Basarida hedefi, hatada H_YOK dondurur.
// bekleyen: uzun aktarimda (Bluetooth) ara ara cagrilir (IDE baglantisi kopmasin diye)
Hedef programUygula(const uint8_t* p, size_t n, String& mesaj, void (*bekleyen)() = nullptr) {
  if (n < 100000 || n > 3000000) { mesaj = "Dosya boyutu uygun değil."; return H_YOK; }
  // ESP32 program dosyasinin 32. baytinda sabit bir isaret olur (bootloader/merged dosyalarinda yok)
  if (p[32] != 0x32 || p[33] != 0x54 || p[34] != 0xCD || p[35] != 0xAB) {
    mesaj = "Bu dosya bir kart programı değil (.ino.bin olmalı; merged, bootloader, partitions değil).";
    return H_YOK;
  }
  imzaNet.sifirla(); imzaBt.sifirla();
  for (size_t i = 0; i < n; i++) { imzaNet.besle(p[i]); imzaBt.besle(p[i]); }
  if (imzaNet.bulundu == imzaBt.bulundu) { mesaj = "Bu program iki karttan birine ait değil (imza bulunamadı)."; return H_YOK; }

  if (imzaNet.bulundu) {
    Kayit.printf("Program internet kartina ait (%u bayt), yaziliyor...\n", (unsigned)n);
    if (!Update.begin(n)) { mesaj = "İnternet kartı güncellemeyi başlatamadı."; return H_YOK; }
    for (size_t i = 0; i < n; i += 4096) {
      size_t u = min((size_t)4096, n - i);
      if (Update.write((uint8_t*)p + i, u) != u) { Update.abort(); mesaj = "İnternet kartına yazılamadı."; return H_YOK; }
    }
    if (!Update.end(true)) { mesaj = "Yeni program doğrulanamadı; eski program çalışmaya devam ediyor."; return H_YOK; }
    mesaj = "İnternet kartı güncellendi, yeniden başlıyor.";
    return H_NET;
  }

  Kayit.printf("Program Bluetooth kartina ait (%u bayt), kabloyla aktariliyor...\n", (unsigned)n);
  if (!btBasla(n)) { mesaj = "Bluetooth kartı cevap vermedi. Kartın açık ve kabloların takılı olduğunu kontrol edin."; return H_YOK; }
  uint32_t sonBekleme = millis();
  for (size_t i = 0; i < n; i += 1436) {
    uint16_t u = (uint16_t)min((size_t)1436, n - i);
    if (btCerceve('B', p + i, u, 1500) != 'A') { btIptal(); mesaj = "Bluetooth kartına aktarım başarısız (kablo bağlantısını kontrol edin)."; return H_YOK; }
    gSira++;
    if (millis() - sonBekleme > 2000) { sonBekleme = millis(); mqtt.loop(); if (bekleyen) bekleyen(); }
  }
  char r = btCerceve('E', nullptr, 0, 10000);
  btHizNormal();
  if (r != 'A') { mesaj = "Bluetooth kartı yeni programı doğrulayamadı; eski program çalışmaya devam ediyor."; return H_YOK; }
  sonBluetoothMesaji = 0;
  mesaj = "Bluetooth kartı güncellendi, yeniden başlıyor.";
  return H_BT;
}

// ---- Arduino IDE'den ag yuklemesi (espota) ----
WiFiClient* otaIstemci = nullptr;
uint32_t otaSonSinyal = 0;
void otaBekleyen() {   // IDE "Waiting for result" sirasinda 60 sn'de vazgecer; ara ara isaret gonder
  if (otaIstemci && millis() - otaSonSinyal > 15000) { otaSonSinyal = millis(); otaIstemci->print("."); }
}

void otaKontrol() {
  int paket = otaUdp.parsePacket();
  if (!paket) return;
  char m[96];
  int l = otaUdp.read(m, sizeof(m) - 1);
  if (l <= 0) return;
  m[l] = 0;
  int komut; unsigned port; unsigned long boyut; char md5[40];
  if (sscanf(m, "%d %u %lu %32s", &komut, &port, &boyut, md5) != 4 || komut != 0) return;   // 0 = program yukleme
  IPAddress ip = otaUdp.remoteIP();
  if (boyut < 100000 || boyut > 3000000) {
    otaUdp.beginPacket(ip, otaUdp.remotePort()); otaUdp.print("Dosya boyutu uygun degil"); otaUdp.endPacket();
    return;
  }
  uint8_t* tampon = (uint8_t*)ps_malloc(boyut);
  if (!tampon) {
    otaUdp.beginPacket(ip, otaUdp.remotePort()); otaUdp.print("Bellek yok (PSRAM)"); otaUdp.endPacket();
    Kayit.println("Ag yuklemesi: PSRAM ayrilamadi (Tools > PSRAM: OPI PSRAM olmali).");
    return;
  }
  otaUdp.beginPacket(ip, otaUdp.remotePort()); otaUdp.print("OK"); otaUdp.endPacket();
  Kayit.printf("Arduino IDE'den ag yuklemesi basladi (%lu bayt).\n", boyut);
  gGuncelleme = true;

  WiFiClient c;
  bool ok = false;
  for (int i = 0; i < 10 && !ok; i++) { ok = c.connect(ip, port); if (!ok) delay(100); }
  if (!ok) { Kayit.println("Ag yuklemesi: bilgisayara baglanilamadi (Windows guvenlik duvari izni?)."); free(tampon); gGuncelleme = false; return; }
  c.setNoDelay(true);

  size_t alinan = 0;
  uint32_t son = millis();
  while (alinan < boyut && millis() - son < 10000) {
    int av = c.available();
    if (!av) { if (!c.connected()) break; delay(1); continue; }
    int r = c.read(tampon + alinan, min((size_t)av, (size_t)(boyut - alinan)));
    if (r > 0) { alinan += r; son = millis(); c.print(alinan); }   // IDE her parcadan sonra cevap bekler
  }
  String mesaj;
  Hedef h = H_YOK;
  if (alinan != boyut) mesaj = "Yukleme yarida kesildi.";
  else {
    MD5Builder md; md.begin(); md.add(tampon, boyut); md.calculate();
    if (!md.toString().equalsIgnoreCase(md5)) mesaj = "Dosya bozuk geldi (MD5 tutmadi).";
    else {
      otaIstemci = &c; otaSonSinyal = millis();
      h = programUygula(tampon, boyut, mesaj, otaBekleyen);
      otaIstemci = nullptr;
    }
  }
  free(tampon);
  gGuncelleme = false;
  Kayit.printf("Ag yuklemesi: %s\n", mesaj.c_str());
  if (h != H_YOK) c.print("OK");
  else c.print("HATA");
  c.flush();
  delay(300);
  c.stop();
  if (h == H_NET) { delay(300); ESP.restart(); }
}

// ---------------------------------------------------------------------
void yardimGoster() {
  Kayit.println();
  Kayit.printf("=========== Internet karti · surum %s ===========\n", surum());
  if (WiFi.status() == WL_CONNECTED) Kayit.printf(" Wi-Fi: bagli, IP %s\n", WiFi.localIP().toString().c_str());
  else Kayit.println(" Wi-Fi: YOK");
  Kayit.printf(" Sunucu: %s\n", mqtt.connected() ? "bagli" : "YOK");
  Kayit.printf(" Bluetooth karti: %s, surum %s\n", bluetoothKartiVar() ? "bagli" : "SESSIZ", btSurum);
  Kayit.println(" Ag yuklemesi: Arduino IDE > Tools > Port > \"stand at ...\" > Upload (iki kart icin de)");
  Kayit.println(" (IDE sifre sorarsa herhangi bir sey yazin, ornegin 1.)");
  Kayit.println(" Bluetooth kartinin yazilari burada [BT] ile gorunur.");
  Kayit.println(" Harf komutlari sadece USB takili karta gider. Bu kartta sadece ? var.");
  Kayit.println("==================================================");
}

void isikGuncelle() {
  static uint32_t son = 0;
  if (millis() - son < 400) return;
  son = millis();
  if (WiFi.status() != WL_CONNECTED || !mqtt.connected()) isik(40, 0, 0);   // kirmizi: internet veya sunucu yok
  else if (!bluetoothKartiVar()) isik(30, 20, 0);                            // turuncu: Bluetooth kartindan ses yok
  else isik(0, 25, 0);                                                        // yesil: her sey yolunda
}

// =====================================================================
void setup() {
  Serial.begin(115200);
  delay(1200);
  oturum = (uint16_t)(esp_random() & 0xFFFF);
  Kayit.printf("\n=== Internet karti (ozel sunucu) · surum %s ===\n", surum());
  isik(40, 0, 0);
  imzalariHazirla();

  Serial1.setRxBufferSize(4096);
  Serial1.setTxBufferSize(2048);
  Serial1.begin(HIZ_NORMAL, SERIAL_8N1, HAT_RX, HAT_TX);

  konuKomut = String("comitok/") + ODA_KODU + "/komut";
  konuVar   = String("comitok/") + ODA_KODU + "/var";
  konuDurum = String("comitok/") + ODA_KODU + "/durum";
  konuKayit = String("comitok/") + ODA_KODU + "/kayit";

  wifiBaglan();
  saatiAyarla();
  guvenli.setCACert(KOK_SERTIFIKA);
  mqtt.setServer(SUNUCU, SUNUCU_PORT);
  mqtt.setBufferSize(2560);
  mqtt.setKeepAlive(30);
  mqtt.setCallback(sunucudanGeldi);
  sunucuyaBaglan();

  otaUdp.begin(OTA_PORT);
  Kayit.println("Tum bilgiler icin ? yazip Enter'a basin.");
}

void loop() {
  uint32_t n = millis();

  if (WiFi.status() != WL_CONNECTED) { static uint32_t sw = 0; if (n - sw > 10000) { sw = n; wifiBaglan(); } }
  if (!mqtt.connected()) {
    if (sunucuBagliydi) { sunucuBagliydi = false; Kayit.println("Sunucu baglantisi koptu, yeniden denenecek."); }
    static uint32_t sm = 0;
    if (n - sm > sunucuBekle) { sm = n; sunucuyaBaglan(); }
  }
  mqtt.loop();
  otaKontrol();

  hatOku();

  // Bluetooth kartina "buradayim" isareti
  if (n - sonIsaret > 2000) { sonIsaret = n; Serial1.print("C1\n"); }

  // Bluetooth karti sessizse sayfaya bildir (degismedigi surece dakikada bir gider)
  if (!bluetoothKartiVar() && mqtt.connected() && n - sonYedekDurum > 3000) {
    sonYedekDurum = n;
    const char* d = "{\"stand\":false,\"izin\":false,\"kumanda\":false,\"cihaz\":\"Bluetooth kartı yanıt vermiyor\"}";
    durumYayinla(d, strlen(d));
    static uint32_t sonUyari = 0;
    if (n - sonUyari > 10000) { sonUyari = n; Kayit.println("Bluetooth kartindan ses yok (kablolari kontrol edin)."); }
  }

  if (Serial.available()) {
    char c = Serial.read();
    if (c == '?') yardimGoster();
  }

  isikGuncelle();
  delay(2);
}
