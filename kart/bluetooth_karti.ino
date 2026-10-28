// =====================================================================
//  BLUETOOTH KARTI (ESP32-S3) - iki kart duzeni
//  stand_kopru.ino'nun son halinden turetildi. Wi-Fi KULLANMAZ.
//  Stand, kumanda (kopru, her zaman acik) ve iPhone'a Bluetooth ile baglanir.
//  Internet kartiyla 3 kablo uzerinden konusur:
//    bu kartin GPIO17 (TX)  -> internet kartinin GPIO18 (RX)
//    bu kartin GPIO18 (RX)  <- internet kartinin GPIO17 (TX)
//    GND <-> GND
//
//  4 Ekim 2026:
//   - Donus siniri (kablo sarilmasin): baslangica gore varsayilan +-540 derece.
//   - Bu kartin yazilari internet kartina da gider (sayfadaki Kayit bolumu).
//   - Kablosuz guncelleme: internet karti yeni programi kabloyla bu karta aktarir.
//   - Temizlik: kumanda pil deneme kodu kaldirildi; sifirla/gorunum/kontroller yonetici ister.
//
//  5 Ekim 2026:
//   - Bilgisayara Bluetooth klavye + fare (klavye.html sayfasindan). iPhone ile karismaz:
//     her tus sadece hedef cihaza gider. Bilgisayar bir kez "eslestirme modu" ile tanitilir.
//   - Bluetooth baglanti siniri 3'ten 5'e cikarildi. build_opt.h kullaniciinin bilgisayarinda calismadi;
//     ayar NimBLE kutuphanesinin nimconfig.h dosyasinda yapildi (MAX_CONNECTIONS 5, MAX_BONDS 6, MAX_CCCDS 24).
//   - (2. surum) Fare gecikmesi: fare/tus komutlarindan sonra durum gonderilmiyor (kablo tikaniyordu).
//     Canli yazma icin silme sayisi, tam klavye ve kisayollar icin genel tus kombinasyonu (pcKombo).
//
//  14 Ekim 2026:
//   - Sayfadan "k180" komutu: fiziksel kumandanin 180 tusunun aynisi (ayni gidis-donus sirasi,
//     ayni 180 modu ve hibrit ayarlari). Saat kumandasi bunu kullanir.
//   - Durum mesajina "donuyor" (180/konum gibi uzun hareket suruyor mu) ve "sira180"
//     (hibritte siradaki 180 gidis mi donus mu) eklendi. Donmeye baslayinca/durunca durum hemen gider.
//
//  15 Ekim 2026:
//   - Izin sarti kalkti: kart acilir acilmaz uzaktan kontrol acik (joystick, 180, konum, takip).
//     Kart yeniden baslasa da Seda ve Pixel'in takibi calisir. Kim kontrol eder kurali ileride
//     sayfalarda olacak. Durumda "izin" hep true gider (eski sayfalar degismeden calissin).
//     Baslangic, yeniden baslatma, ayarlar, bilgisayar eslestirme ve iPhone kamera izni yine yonetici ister.
//   - Gelen arama geri geldi (6 Ekim "5 Ekim 2026 · 3" surumundeki ANCS kodu, 10 Ekim'de kaybolmustu):
//     iPhone'a gelen arama gorulur (Kayit + durum "arama"). Kabul/ret: kumanda deklansoru
//     (tek = kabul, cift = ret), sayfa komutlari aramaKabul/aramaRed (yonetici), Serial a / n.
//     Arama calarken ekrana dokunan islemler yapilmaz.
// =====================================================================

#include <ArduinoJson.h>
#include <NimBLEDevice.h>
#include <NimBLEHIDDevice.h>
#if CONFIG_BT_NIMBLE_MAX_CONNECTIONS < 5
#error "Bluetooth baglanti siniri 3: Documents/Arduino/libraries/NimBLE-Arduino/src/nimconfig.h icinde MAX_CONNECTIONS 5, MAX_BONDS 6, MAX_CCCDS 24 yapin."
#endif
#include <Preferences.h>
#include <Update.h>

// Kisisel bilgiler (Wi-Fi, oda kodu, yonetici kodu) ayri dosyada: ayarlar.h
// Bu dosya guncellense bile ayarlar.h degismez.
#include "ayarlar.h"

#ifndef RGB_BUILTIN
#define RGB_BUILTIN 48
#endif

// Kart imzasi ve surumu. Kablosuz guncellemede yanlis kartin dosyasi yuklenmesin diye
// internet karti dosyada bu imzayi arar. Degistirmeyin.
const char IMZA[] = "STANDKARTI:BLUETOOTH|27 Ekim 2026 · 1";
const char* surum() { return strchr(IMZA, '|') + 1; }

bool gAktif = false;   // kablosuz guncelleme suruyor mu

// ---------------------------------------------------------------------
//  KAYIT: Serial Monitor'a yazar, ayrica her satiri internet kartina yollar
//  (internet karti kendi Serial'ine [BT] ile yazar ve sayfaya iletir).
//  Bluetooth olaylari baska gorevden de yazabildigi icin satirlar kuyrukta bekler,
//  kabloya sadece ana dongu yazar.
// ---------------------------------------------------------------------
portMUX_TYPE kayitKilit = portMUX_INITIALIZER_UNLOCKED;
const int KUYRUK = 24, SATIR_UZ = 160;
char kuyruk[KUYRUK][SATIR_UZ];
volatile int kuyrukBas = 0, kuyrukSon = 0;
char kayitSatir[SATIR_UZ];
int kayitN = 0;

class KayitYazici : public Print {
 public:
  size_t write(uint8_t c) override {
    Serial.write(c);
    portENTER_CRITICAL(&kayitKilit);
    if (c == '\n') {
      kayitSatir[kayitN] = 0;
      memcpy(kuyruk[kuyrukSon], kayitSatir, kayitN + 1);
      kuyrukSon = (kuyrukSon + 1) % KUYRUK;
      if (kuyrukSon == kuyrukBas) kuyrukBas = (kuyrukBas + 1) % KUYRUK;   // dolduysa en eskiyi at
      kayitN = 0;
    } else if (c != '\r' && kayitN < SATIR_UZ - 1) {
      kayitSatir[kayitN++] = (c == '\t') ? ' ' : c;
    }
    portEXIT_CRITICAL(&kayitKilit);
    return 1;
  }
  size_t write(const uint8_t* b, size_t n) override {
    for (size_t i = 0; i < n; i++) write(b[i]);
    return n;
  }
};
KayitYazici Kayit;

// Kuyruktaki satirlari kabloyla internet kartina gonder: L<metin>
void kayitGonder() {
  if (gAktif) return;
  char tmp[SATIR_UZ];
  int adet = 0;
  while (adet < 8) {
    portENTER_CRITICAL(&kayitKilit);
    bool bos = kuyrukBas == kuyrukSon;
    if (!bos) { memcpy(tmp, kuyruk[kuyrukBas], SATIR_UZ); kuyrukBas = (kuyrukBas + 1) % KUYRUK; }
    portEXIT_CRITICAL(&kayitKilit);
    if (bos) break;
    if (!tmp[0]) continue;
    Serial1.write('L');
    Serial1.write((const uint8_t*)tmp, strlen(tmp));
    Serial1.write('\n');
    adet++;
  }
}

// ---- kartlar arasi hat ----
const int HAT_RX = 18, HAT_TX = 17;
uint32_t sonHatMesaji = 0;            // internet kartindan son mesaj zamani
const uint32_t HAT_SESSIZ = 8000;     // bu kadar sessizlikte internet karti yok sayilir

// ---- stand ----
static const char* NUS_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char* RX_UUID  = "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
static const char* TX_UUID  = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";
const uint16_t ORTA = 510, SAPMA = 190;
uint32_t ARALIK = 40;                 // paket araligi (ms); Serial Monitor'a h yazarak 20/30/40/60/80 arasinda degistirilir
const uint32_t GECIKME = 250;         // standin harekete baslama gecikmesi (ms)
const uint32_t YARIM_TUR = 6600;      // 180 derece (ms)
const float DONUS_HIZI = 27.0;        // derece / saniye
const float EGME_HIZI = 27.0;         // derece / saniye
const uint32_t JOY_BEKLEME = 600;     // joystick komutu kesilirse dur (ms)
const uint32_t UYANIK_TUT = 240000;   // 4 dk hareketsizlikte kucuk kipirdama

// ---- durum ----
NimBLEClient* istemci = nullptr;
NimBLERemoteCharacteristic* rx = nullptr;

// kumanda (kopru)
NimBLEClient* kumandaIstemci = nullptr;
volatile bool kumandaYeni = false;
uint8_t kumandaTampon[14];
uint8_t oncekiKamera = 0, oncekiStand = 0;
int8_t kumDx = 0, kumDy = 0;
uint16_t kumX = 510, kumY = 510;          // kumandanin gercek eksen degerleri (yon ayari uygulanmis)
const uint32_t KUMANDA_ARALIK = 30;       // kumanda hareketinde paket araligi (ms)
uint32_t kumHareketBasi = 0;
int8_t yarimTurYonu = 1;
uint8_t mod180 = 2;          // 0: standin kendi, 1: akilli (yavas, yon degistirir), 2: hibrit (gidis stand, donus kart)
int8_t yerli180Yonu = 1;     // standin kendi 180'inin yonu (o ile degisir)
uint32_t geriDonusMs = 3600; // hibrit donus suresi ([ ve ] ile ayarlanir)
bool hibritGidildi = false;  // hibritte bir sonraki basis donus mu
uint32_t yerliBitis = 0;     // standin kendi 180'inin tahmini bitis zamani (millis); 0 = yok
bool sonDonuyor = false;     // durum mesajindaki "donuyor" en son ne idi
Preferences hafiza;
bool kumTersX = true;    // kumandanin yatay yonu ters (varsayilan: ters)
bool kumTersY = false;   // kumandanin dikey yonu
bool kopruAcik = true;         // iki kart duzeninde kumanda koprusu her zaman acik
bool standAra = true;        // test: n ile kapatilir
// Kumanda aramasi (20 Ekim): stand bagliyken kumanda aranmaz (anten paylasimi standi dusuruyordu).
// Stand bagli degilken hizli aranir; stand bagliyken sadece sayfadaki "Kumandayi ara" ile 45 sn.
const uint32_t KUM_ARA_MS = 45000;
uint32_t kumAraBitis = 0;            // 0: elle arama yok
bool kumAramaAcik() { return kumAraBitis && (int32_t)(kumAraBitis - millis()) > 0; }
// Stand koparsa kayda "o sirada ne oluyordu" yazmak icin
volatile bool kumBaglaniyor = false, taramaSuruyor = false, sonTaramaKumanda = false;
volatile uint32_t sonTaramaBitis = 0;
bool kumandaKalp = false;
bool kumandaEslesme = false;  // kumanda eslesmeyi kabul etmiyor; e ile acilir

// iPhone'a medya tusu cihazi olarak baglanti
NimBLEServer* hidSunucu = nullptr;
NimBLEHIDDevice* hid = nullptr;
NimBLECharacteristic* medyaRaporu = nullptr;
NimBLERemoteCharacteristic* kumandaRx = nullptr;
uint32_t sonKalp = 0, kumandaBaglanmaZamani = 0;  // kumandayi karta baglamayi dene (varsayilan kapali: kumanda standa kendi baglanir)
const uint32_t KUMANDA_EN_UZUN = 20000;  // guvenlik: kumanda sessiz kalirsa en fazla 20 sn hareket

bool kameraIzin = false;   // iPhone kamerasini uzaktan kontrol izni (ayri, acilista kapali)
float netDonus = 0, netEgme = 0;
float donusSiniri = 540;     // baslangica gore en fazla bu kadar donulur (iki yone de)
uint32_t sinirdeZaman = 0;   // donus sinirina en son ne zaman takildi
uint32_t sonHareket = 0, sonPaket = 0, sonDurumYayin = 0, sonStandDenemesi = 0, sonCanliTut = 0;

// zamanli hareket
struct Hareket { bool aktif = false; int8_t dx = 0, dy = 0; uint32_t bas = 0, sure = 0; bool tam = false;
                 bool ayri = false; uint32_t sureX = 0, sureY = 0; } hr;   // ayri: takip adimi (yatay/dikey ayri sureli)
struct Sonraki { bool var = false; int8_t dx = 0, dy = 0; uint32_t sure = 0, zaman = 0; } sonraki;

// joystick
int8_t joyDx = 0, joyDy = 0;
uint32_t joyZaman = 0, joyBasla = 0;
bool joyVardi = false;

// durdurma darbesi
uint32_t durdurZaman[3] = {0, 0, 0};

// bagli kisiler
struct Kisi { char id[16]; char cihaz[48]; uint32_t zaman; };
Kisi kisiler[6];

// =====================================================================
void isik(uint8_t r, uint8_t g, uint8_t b) { rgbLedWrite(RGB_BUILTIN, r, g, b); }

uint16_t crc16(const uint8_t* d, size_t n) {
  uint16_t c = 0;
  for (size_t i = 0; i < n; i++) {
    c ^= d[i];
    for (int k = 0; k < 8; k++) c = (c & 1) ? (c >> 1) ^ 0xA001 : (c >> 1);
  }
  return c;
}

void durumYayinla();
bool standBagli() { return istemci && istemci->isConnected() && rx; }

bool kumandaBagli() { return kumandaIstemci && kumandaIstemci->isConnected(); }
extern bool kopruAcik;
bool kumAramaAcik();
bool kumandaAranmali() { return kopruAcik && !kumandaBagli() && (!standBagli() || kumAramaAcik()); }

extern uint32_t kumandaBaglanmaZamani;
const char* kopmaAciklama(int r) {
  switch (r & 0xFF) {
    case 0x08: return "zaman asimi (sinyal veya anten mesgul)";
    case 0x13: return "karsi taraf baglantiyi kendi kesti";
    case 0x16: return "kart kesti";
    case 0x3E: return "baglanti kurulamadi";
    default:   return "diger";
  }
}

class BaglantiTakip : public NimBLEClientCallbacks {
 public:
  explicit BaglantiTakip(const char* ad) : ad_(ad) {}
  void onDisconnect(NimBLEClient* c, int sebep) override {
    Kayit.printf("! [%lus] %s koptu: kod 0x%02X, %s", (unsigned long)(millis() / 1000), ad_, sebep & 0xFF, kopmaAciklama(sebep));
    if (strcmp(ad_, "Kumanda") == 0 && kumandaBaglanmaZamani)
      Kayit.printf(" (bagli kalma: %lu sn)", (unsigned long)((millis() - kumandaBaglanmaZamani) / 1000));
    if (strcmp(ad_, "Stand") == 0) {
      if (kumBaglaniyor) Kayit.print(" (o sirada: kumandaya baglaniliyordu)");
      else if (taramaSuruyor || (millis() - sonTaramaBitis < 3000))
        Kayit.print(sonTaramaKumanda ? " (o sirada: kumanda araniyordu)" : " (o sirada: tarama vardi)");
      else Kayit.print(" (o sirada: tarama yoktu)");
    }
    Kayit.println();
    if (strcmp(ad_, "Kumanda") == 0 && standBagli() && kopruAcik)
      Kayit.println("Stand bagli oldugu icin kumanda aranmiyor. Sayfadan: Ayarlar > Kumandayi ara (45 sn).");
  }
 private:
  const char* ad_;
};
BaglantiTakip standTakip("Stand");
BaglantiTakip kumandaTakip("Kumanda");

void paketGonder(uint16_t x, uint16_t y, uint8_t kamera = 0, uint8_t standTus = 0) {
  if (!standBagli()) return;
  uint8_t b[14] = {0x55, 0x0E, 0x69, 0x13, 0x50, kamera, 0x00,
                   (uint8_t)(x & 0xFF), (uint8_t)(x >> 8),
                   (uint8_t)(y & 0xFF), (uint8_t)(y >> 8), standTus, 0, 0};
  uint16_t c = crc16(b, 12);
  b[12] = c >> 8; b[13] = c & 0xFF;
  rx->writeValue(b, 14, false);
  if (x != ORTA || y != ORTA) sonHareket = millis();
}
void bosta() { paketGonder(ORTA, ORTA); }

void durdurmaDarbesi() {
  bosta();
  uint32_t n = millis();
  durdurZaman[0] = n + 90; durdurZaman[1] = n + 220; durdurZaman[2] = n + 450;
}

void sayacEkle(int8_t dx, int8_t dy, uint32_t ms) {
  netDonus += dx * DONUS_HIZI * ms / 1000.0;
  netEgme = constrain(netEgme + dy * EGME_HIZI * ms / 1000.0, -45.0, 45.0);
}

// ---------------------------------------------------------------------
//  DONUS SINIRI (kablolar sarilmasin)
// ---------------------------------------------------------------------
// Bu yone bu kadar donmek siniri asar mi? (Sinirdan iceri, yani baslangica dogru donus hep serbest.)
bool sinirAsar(int8_t yon, float derece) {
  if (donusSiniri <= 0) return false;   // sinirsiz
  float yeni = netDonus + yon * derece;
  return fabs(yeni) > donusSiniri && fabs(yeni) > fabs(netDonus);
}
// Joystick: sinira gelindiyse o yondeki donus durdurulur
bool sinirdaMi(int8_t yon) {
  if (donusSiniri <= 0) return false;   // sinirsiz
  return (yon > 0 && netDonus >= donusSiniri) || (yon < 0 && netDonus <= -donusSiniri);
}
void sinirUyarisi(const char* ne) {
  uint32_t n = millis();
  bool yeni = !sinirdeZaman || n - sinirdeZaman > 3000;
  sinirdeZaman = n;
  if (yeni) {
    Kayit.printf("Donus siniri (+-%d derece): %s\n", (int)donusSiniri, ne);
    durumYayinla();
  }
}

bool hareketBaslat(int8_t dx, int8_t dy, uint32_t sure) {
  if (hr.aktif || !standBagli()) return false;
  if (dx && sure > GECIKME && sinirAsar(dx, DONUS_HIZI * (sure - GECIKME) / 1000.0)) {
    sinirUyarisi("bu yonde daha fazla donulmez");
    return false;
  }
  hr.aktif = true; hr.dx = dx; hr.dy = dy; hr.bas = millis(); hr.sure = sure; hr.tam = false; hr.ayri = false;
  sonPaket = 0;
  Kayit.printf("> hareket dx=%d dy=%d %lu ms\n", dx, dy, (unsigned long)sure);
  return true;
}

// ---------------------------------------------------------------------
//  TAKIP ADIMI (10 Ekim 2026)
// ---------------------------------------------------------------------
// Pixel tek komutla "su yone su kadar ms don" der; sureyi kart kendi saatiyle tutar.
// Boylece internetteki gecikme donus miktarini bozmaz. Yatay ve dikey ayri sureli olabilir (capraz).
// Ayni guvenlik kurallari gecerli: donus siniri. Baska bir komut gelirse hemen biter.
// Her adim icin kayit yazilmaz (kayitlar sunucuya da gidiyor, kotayi doldurmasin).
void adimBitir(uint32_t n) {
  uint32_t g = n - hr.bas;
  uint32_t ex = g < hr.sureX ? g : hr.sureX, ey = g < hr.sureY ? g : hr.sureY;
  if (hr.dx) sayacEkle(hr.dx, 0, ex > GECIKME ? ex - GECIKME : 0);
  if (hr.dy) sayacEkle(0, hr.dy, ey > GECIKME ? ey - GECIKME : 0);
  hr.aktif = false; hr.ayri = false;
}
void takipAdimi(int8_t dx, int8_t dy, uint32_t msX, uint32_t msY) {
  if (!standBagli()) return;
  if (hr.aktif && !hr.ayri) return;              // 180, konum gibi bir hareket suruyor: dokunma
  if (hr.aktif) adimBitir(millis());             // onceki takip adimi yarida kaldi: sayaci isle
  if (msX > 1500) msX = 1500;
  if (msY > 1500) msY = 1500;
  if (!dx) msX = 0;
  if (!dy) msY = 0;
  if (dx && msX > GECIKME && sinirAsar(dx, DONUS_HIZI * (msX - GECIKME) / 1000.0)) {
    sinirUyarisi("takip bu yonde daha fazla donmez");
    dx = 0; msX = 0;
  }
  if (!msX && !msY) return;
  hr.aktif = true; hr.ayri = true; hr.tam = false;
  hr.dx = dx; hr.dy = dy; hr.sureX = msX; hr.sureY = msY;
  hr.sure = msX > msY ? msX : msY; hr.bas = millis();
  sonPaket = 0;
}

void herSeyiDurdur() {
  hr.aktif = false; sonraki.var = false;
  joyDx = 0; joyDy = 0; joyZaman = 0; joyBasla = 0; joyVardi = false;
  kumDx = 0; kumDy = 0;
  durdurmaDarbesi();
  yerliBitis = 0;
  Kayit.println("> dur");
}

void hedefeGit(float hedefDonus, bool egmeVar, float hedefEgme) {
  if (hr.aktif) { Kayit.println("Mesgul."); return; }
  // Konum sinirin disinda kaydedildiyse (kablo sariliyken), ayni yone bakan icerdeki karsiligina git
  float ilk = hedefDonus;
  if (donusSiniri > 0) {
    while (hedefDonus > donusSiniri) hedefDonus -= 360;
    while (hedefDonus < -donusSiniri) hedefDonus += 360;
  }
  if (hedefDonus != ilk) Kayit.printf("Konum %d derece sinir disinda, ayni yone bakan %d dereceye gidiliyor.\n", (int)ilk, (int)hedefDonus);
  float fark = hedefDonus - netDonus;
  uint32_t ms = fabs(fark) / DONUS_HIZI * 1000;
  if (ms > 45000) ms = 45000;

  uint32_t tms = 0; int8_t tyon = 0;
  if (egmeVar) {
    float tf = hedefEgme - netEgme;
    tms = fabs(tf) / EGME_HIZI * 1000;
    if (tms > 6000) tms = 6000;
    tyon = tf > 0 ? 1 : -1;
  }
  if (ms < 120) {
    if (tms >= 120) hareketBaslat(0, tyon, tms + GECIKME);
    return;
  }
  if (tms >= 120) { sonraki.var = true; sonraki.dx = 0; sonraki.dy = tyon; sonraki.sure = tms + GECIKME; sonraki.zaman = 0; }
  hareketBaslat(fark > 0 ? 1 : -1, 0, ms + GECIKME);
}

void hareketIsle() {
  uint32_t n = millis();

  // durdurma darbeleri
  for (int i = 0; i < 3; i++) if (durdurZaman[i] && n >= durdurZaman[i]) { bosta(); durdurZaman[i] = 0; }

  // takip adimi: kumanda (MOTA) kullanilirsa hemen biter
  if (hr.aktif && hr.ayri && (kumDx || kumDy) && kumandaBagli()) { adimBitir(n); durdurmaDarbesi(); }

  // takip adimi: yatay ve dikey kendi suresi dolunca ayri ayri durur
  if (hr.aktif && hr.ayri) {
    uint32_t g = n - hr.bas;
    if (g >= hr.sure || !standBagli()) { adimBitir(n); durdurmaDarbesi(); }
    else if (n - sonPaket >= ARALIK) {
      int8_t ex = g < hr.sureX ? hr.dx : 0, ey = g < hr.sureY ? hr.dy : 0;
      if (ex && sinirdaMi(ex)) { ex = 0; sinirUyarisi("takip bu yonde durduruldu"); }
      paketGonder(ORTA + ex * SAPMA, ORTA + ey * SAPMA);
      sonPaket = n;
    }
    return;
  }

  // zamanli hareket
  if (hr.aktif) {
    if (n - hr.bas >= hr.sure || !standBagli()) {
      hr.aktif = false;
      durdurmaDarbesi();
      uint32_t etkin = hr.sure > GECIKME ? hr.sure - GECIKME : 0;
      if (hr.tam && hr.dx) netDonus += hr.dx * 180.0;   // hibrit donus: tam 180 kabul edilir
      else sayacEkle(hr.dx, hr.dy, etkin);
      if (sonraki.var) sonraki.zaman = n + 330;
    } else if (n - sonPaket >= ARALIK) {
      uint16_t sap = hr.tam ? 510 : SAPMA;
      paketGonder(ORTA + hr.dx * sap, ORTA + hr.dy * sap);
      sonPaket = n;
    }
    return;
  }

  // sirada bekleyen hareket
  if (sonraki.var && sonraki.zaman && n >= sonraki.zaman) {
    sonraki.var = false;
    hareketBaslat(sonraki.dx, sonraki.dy, sonraki.sure);
    return;
  }

  // joystick: once sayfadan gelen, yoksa kumanda
  bool webAktif = (n - joyZaman < JOY_BEKLEME) && (joyDx || joyDy);
  bool kumAktif = (kumDx || kumDy) && kumandaBagli() && (n - kumHareketBasi < KUMANDA_EN_UZUN);
  bool joyAktif = webAktif || kumAktif;
  int8_t ex = webAktif ? joyDx : kumDx;
  int8_t ey = webAktif ? joyDy : kumDy;
  uint32_t aralik = webAktif ? ARALIK : KUMANDA_ARALIK;
  if (joyAktif) {
    if (n - sonPaket >= aralik) {
      if (ex && sinirdaMi(ex)) { ex = 0; sinirUyarisi("joystick bu yonde durduruldu"); }
      uint16_t px = webAktif ? ORTA + ex * SAPMA : (ex ? kumX : ORTA);
      uint16_t py = webAktif ? ORTA + ey * SAPMA : kumY;
      paketGonder(px, py);
      if (!joyBasla) joyBasla = n;
      if (n - joyBasla > GECIKME) sayacEkle(ex, ey, aralik);
      sonPaket = n;
      joyVardi = true;
    }
  } else if (joyVardi) {
    joyVardi = false; joyBasla = 0;
    durdurmaDarbesi();
  }

  // hazir bekle: stand 5 dk hareketsiz kalirsa kapaniyor
  if (!joyAktif && !sonraki.var && standBagli() && n - sonHareket > UYANIK_TUT) {
    Kayit.println("Hazir bekle: kucuk kipirdama");
    int8_t yon = netDonus > 0 ? -1 : 1;   // once baslangica dogru, sonra geri
    sonraki.var = true; sonraki.dx = -yon; sonraki.dy = 0; sonraki.sure = 450; sonraki.zaman = 0;
    hareketBaslat(yon, 0, 450);
  }
}

// ---------------------------------------------------------------------
//  IPHONE: MEDYA TUSU CIHAZI (selfie kumandasi gibi)
// ---------------------------------------------------------------------
static const uint8_t RAPOR_HARITASI[] = {
  // --- Rapor 1: medya tuslari ---
  0x05, 0x0C,        // Usage Page (Consumer)
  0x09, 0x01,        // Usage (Consumer Control)
  0xA1, 0x01,        // Collection (Application)
  0x85, 0x01,        //   Report ID (1)
  0x15, 0x00,        //   Logical Min (0)
  0x26, 0xFF, 0x03,  //   Logical Max (1023)
  0x19, 0x00,        //   Usage Min (0)
  0x2A, 0xFF, 0x03,  //   Usage Max (1023)
  0x75, 0x10,        //   Report Size (16)
  0x95, 0x01,        //   Report Count (1)
  0x81, 0x00,        //   Input (Data, Array)
  0xC0,              // End Collection
  // --- Rapor 2: fare (5 dugme, hareket yok) ---
  0x05, 0x01,        // Usage Page (Generic Desktop)
  0x09, 0x02,        // Usage (Mouse)
  0xA1, 0x01,        // Collection (Application)
  0x85, 0x02,        //   Report ID (2)
  0x09, 0x01,        //   Usage (Pointer)
  0xA1, 0x00,        //   Collection (Physical)
  0x05, 0x09,        //     Usage Page (Buttons)
  0x19, 0x01,        //     Usage Min (1)
  0x29, 0x05,        //     Usage Max (5)
  0x15, 0x00,        //     Logical Min (0)
  0x25, 0x01,        //     Logical Max (1)
  0x95, 0x05,        //     Report Count (5)
  0x75, 0x01,        //     Report Size (1)
  0x81, 0x02,        //     Input (Data, Var, Abs)
  0x95, 0x01,        //     Report Count (1)
  0x75, 0x03,        //     Report Size (3)
  0x81, 0x01,        //     Input (Const) - dolgu
  0x05, 0x01,        //     Usage Page (Generic Desktop)
  0x09, 0x30,        //     Usage (X)
  0x09, 0x31,        //     Usage (Y)
  0x09, 0x38,        //     Usage (Wheel)
  0x15, 0x81,        //     Logical Min (-127)
  0x25, 0x7F,        //     Logical Max (127)
  0x75, 0x08,        //     Report Size (8)
  0x95, 0x03,        //     Report Count (3)
  0x81, 0x06,        //     Input (Data, Var, Rel)
  0xC0,              //   End Collection
  0xC0,              // End Collection
  // --- Rapor 3: klavye (bilgisayar icin) ---
  0x05, 0x01,        // Usage Page (Generic Desktop)
  0x09, 0x06,        // Usage (Keyboard)
  0xA1, 0x01,        // Collection (Application)
  0x85, 0x03,        //   Report ID (3)
  0x05, 0x07,        //   Usage Page (Key Codes)
  0x19, 0xE0,        //   Usage Min (224) - Ctrl, Shift, Alt, Win
  0x29, 0xE7,        //   Usage Max (231)
  0x15, 0x00,        //   Logical Min (0)
  0x25, 0x01,        //   Logical Max (1)
  0x75, 0x01,        //   Report Size (1)
  0x95, 0x08,        //   Report Count (8)
  0x81, 0x02,        //   Input (Data, Var, Abs) - degistirici tuslar
  0x95, 0x01,        //   Report Count (1)
  0x75, 0x08,        //   Report Size (8)
  0x81, 0x01,        //   Input (Const) - bos bayt
  0x95, 0x05,        //   Report Count (5)
  0x75, 0x01,        //   Report Size (1)
  0x05, 0x08,        //   Usage Page (LEDs)
  0x19, 0x01,        //   Usage Min (1)
  0x29, 0x05,        //   Usage Max (5)
  0x91, 0x02,        //   Output (Data, Var, Abs) - Caps Lock vb. isiklari
  0x95, 0x01,        //   Report Count (1)
  0x75, 0x03,        //   Report Size (3)
  0x91, 0x01,        //   Output (Const) - dolgu
  0x95, 0x06,        //   Report Count (6)
  0x75, 0x08,        //   Report Size (8)
  0x15, 0x00,        //   Logical Min (0)
  0x25, 0x65,        //   Logical Max (101)
  0x05, 0x07,        //   Usage Page (Key Codes)
  0x19, 0x00,        //   Usage Min (0)
  0x29, 0x65,        //   Usage Max (101)
  0x81, 0x00,        //   Input (Data, Array) - basili tuslar
  0xC0               // End Collection
};

NimBLECharacteristic* fareRaporu = nullptr;
NimBLECharacteristic* klavyeRaporu = nullptr;

// ---------------------------------------------------------------------
//  IPHONE VE BILGISAYAR AYRIMI
//  Ikisi de "Stand Kumanda"ya baglanir. Kart kimlik adreslerini hafizada tutar:
//  eslestirme modundayken baglanan cihaz bilgisayar, digerleri iPhone sayilir.
//  Her rapor sadece hedef cihaza gonderilir.
// ---------------------------------------------------------------------
const uint16_t BAGLANTI_YOK = 0xFFFF;
String pcAdres, ipAdres;             // kimlik adresleri (hafizada)
uint8_t pcAdresTipi = 0;
volatile uint32_t pcEslesmeBitis = 0;   // eslestirme modu bitis zamani (0 = kapali)
const uint32_t PC_ESLESME_SURE = 120000;

bool pcEslesmeAcik() { return pcEslesmeBitis && (int32_t)(pcEslesmeBitis - millis()) > 0; }

// pc=true: bilgisayarin baglantisi, pc=false: iPhone'un baglantisi
uint16_t baglantiBul(bool pc) {
  if (!hidSunucu) return BAGLANTI_YOK;
  uint16_t yedek = BAGLANTI_YOK;
  for (uint16_t h : hidSunucu->getPeerDevices()) {
    String a = hidSunucu->getPeerInfoByHandle(h).getIdAddress().toString().c_str();
    bool buPc = pcAdres.length() && a == pcAdres;
    if (pc && buPc) return h;
    if (!pc && !buPc) {
      if (ipAdres.length() && a == ipAdres) return h;
      if (yedek == BAGLANTI_YOK) yedek = h;
    }
  }
  return pc ? BAGLANTI_YOK : yedek;
}
bool iphoneBagli() { return baglantiBul(false) != BAGLANTI_YOK; }
bool pcBagli() { return pcAdres.length() && baglantiBul(true) != BAGLANTI_YOK; }

// ---------------------------------------------------------------------
//  GELEN ARAMA (Apple bildirim servisi, ANCS) - 6 Ekim'de yazildi, 15 Ekim'de geri getirildi
//  Apple Watch ve akilli saatlerin kullandigi yontem: iPhone ile zaten var olan sifreli
//  baglanti uzerinden iPhone'un bildirim servisine abone olunur. Gelen arama (telefon,
//  WhatsApp vb.) gorulur; kabul/ret istegi iPhone'a iletilir, iPhone isi kendisi yapar.
//  iPhone'da: Ayarlar > Bluetooth > Stand Kumanda > "Share System Notifications" acik olmali.
// ---------------------------------------------------------------------
static const char* ANCS_SERVIS  = "7905F431-B5CE-4E99-A40F-4B1E122D00D0";
static const char* ANCS_KAYNAK  = "9FBF120D-6301-42D9-8C58-25E699A21DBD";   // bildirim olaylari
static const char* ANCS_KONTROL = "69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9";   // istek / kabul / ret
static const char* ANCS_VERI    = "22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB";   // istenen bilgiler

NimBLERemoteCharacteristic* ancsKontrol = nullptr;
volatile uint16_t ancsBaglanti = BAGLANTI_YOK;      // ANCS'in dinlendigi iPhone baglantisi
volatile uint16_t ancsKaynakH = 0, ancsVeriH = 0;   // ozellik tutamaclari
volatile uint32_t ancsKurZamani = 0;                // bu zamandan sonra kurulum denenir (0 = istek yok)
uint8_t ancsDeneme = 0;
const uint8_t ANCS_EN_COK_DENEME = 5;

// Bluetooth gorevinden gelen veriler kuyrukta bekler, ana dongu isler
portMUX_TYPE ancsKilit = portMUX_INITIALIZER_UNLOCKED;
const int ANCS_OLAY = 16;
uint8_t ancsOlaylar[ANCS_OLAY][8];
volatile int ancsOlayBas = 0, ancsOlaySon = 0;
uint8_t ancsVeri[600];
volatile uint16_t ancsVeriUz = 0;

// istenen bilgi (ayni anda bir istek)
uint32_t ancsIstenenUid = 0, ancsIstekZamani = 0;
uint8_t ancsIstenenTur = 0, ancsIstenenKat = 0;    // tur: 1 gelen arama, 2 cevapsiz, 3 diger eylemli bildirim

// gelen arama durumu
bool aramaVar = false;
bool aramaBildirildi = false;
uint32_t aramaUid = 0, aramaBasi = 0;
char aramaKim[64] = "";
char aramaUyg[32] = "";

void durumYayinla();

// Bluetooth gorevinde calisir: sadece kopyalar
int ancsGapOlayi(ble_gap_event* e, void* arg) {
  if (e->type != BLE_GAP_EVENT_NOTIFY_RX || e->notify_rx.conn_handle != ancsBaglanti) return 0;
  uint16_t h = e->notify_rx.attr_handle;
  uint16_t n = OS_MBUF_PKTLEN(e->notify_rx.om);
  uint8_t t[256];
  if (n > sizeof(t)) n = sizeof(t);
  os_mbuf_copydata(e->notify_rx.om, 0, n, t);
  if (h == ancsKaynakH && n >= 8) {
    portENTER_CRITICAL(&ancsKilit);
    memcpy(ancsOlaylar[ancsOlaySon], t, 8);
    ancsOlaySon = (ancsOlaySon + 1) % ANCS_OLAY;
    if (ancsOlaySon == ancsOlayBas) ancsOlayBas = (ancsOlayBas + 1) % ANCS_OLAY;
    portEXIT_CRITICAL(&ancsKilit);
  } else if (h == ancsVeriH) {
    portENTER_CRITICAL(&ancsKilit);
    if (ancsVeriUz + n <= sizeof(ancsVeri)) { memcpy(ancsVeri + ancsVeriUz, t, n); ancsVeriUz += n; }
    portEXIT_CRITICAL(&ancsKilit);
  }
  return 0;
}

void ancsSifirla() {
  ancsBaglanti = BAGLANTI_YOK;
  ancsKaynakH = 0; ancsVeriH = 0;
  ancsKontrol = nullptr;
  ancsIstenenTur = 0;
  ancsDeneme = 0;
  ancsKurZamani = 0;
  if (aramaVar) { aramaVar = false; durumYayinla(); }
}

// Ana dongude calisir (Bluetooth sorgulari bekletir)
void ancsKur() {
  ancsKurZamani = 0;
  if (!hidSunucu) return;
  uint16_t h = baglantiBul(false);
  if (h == BAGLANTI_YOK) return;
  // yeniden kurulumda eski nesneler silinecegi icin once dinlemeyi birak
  ancsBaglanti = BAGLANTI_YOK; ancsKaynakH = 0; ancsVeriH = 0; ancsKontrol = nullptr; ancsIstenenTur = 0;
  NimBLEConnInfo ci = hidSunucu->getPeerInfoByHandle(h);
  if (!ci.isEncrypted()) { ancsKurZamani = millis() + 3000; return; }   // sifreleme bitince tekrar
  String a = ci.getIdAddress().toString().c_str();
  if (ipAdres.length() && a != ipAdres) return;                       // iPhone degil
  ancsDeneme++;
  Kayit.println("Gelen aramalar icin iPhone bildirim servisi araniyor...");
  NimBLEClient* c = hidSunucu->getClient(ci);
  NimBLERemoteService* sv = c ? c->getService(ANCS_SERVIS) : nullptr;
  NimBLERemoteCharacteristic* ky = sv ? sv->getCharacteristic(ANCS_KAYNAK) : nullptr;
  NimBLERemoteCharacteristic* kt = sv ? sv->getCharacteristic(ANCS_KONTROL) : nullptr;
  NimBLERemoteCharacteristic* vr = sv ? sv->getCharacteristic(ANCS_VERI) : nullptr;
  if (!ky || !kt || !vr) {
    Kayit.printf("iPhone bildirim servisi bulunamadi (deneme %d/%d).\n", ancsDeneme, ANCS_EN_COK_DENEME);
    if (ancsDeneme < ANCS_EN_COK_DENEME) ancsKurZamani = millis() + 20000;
    else Kayit.println("Vazgecildi. iPhone'da Bluetooth > Stand Kumanda > Share System Notifications acik mi? Kart yeniden baslayinca tekrar denenir.");
    return;
  }
  ancsKaynakH = ky->getHandle();
  ancsVeriH = vr->getHandle();
  ancsKontrol = kt;
  ancsBaglanti = h;
  if (!vr->subscribe(true) || !ky->subscribe(true)) {
    ancsBaglanti = BAGLANTI_YOK; ancsKontrol = nullptr;
    Kayit.printf("iPhone bildirimlere izin vermedi (deneme %d/%d). iPhone'da cikan soruya Allow deyin veya\n", ancsDeneme, ANCS_EN_COK_DENEME);
    Kayit.println("  Ayarlar > Bluetooth > Stand Kumanda (i) > Share System Notifications'i acin.");
    if (ancsDeneme < ANCS_EN_COK_DENEME) ancsKurZamani = millis() + 20000;
    return;
  }
  ancsDeneme = 0;
  Kayit.println("Gelen aramalar dinleniyor (iPhone bildirim servisi hazir).");
  durumYayinla();
}

const char* uygulamaAdi(const char* id) {
  if (!strcmp(id, "com.apple.mobilephone")) return "Telefon";
  if (!strcmp(id, "net.whatsapp.WhatsApp")) return "WhatsApp";
  if (!strcmp(id, "com.apple.facetime"))    return "FaceTime";
  return id;
}

// Yarim kalmis UTF-8 harfini (Turkce harfler 2 bayt) sondan atar
void utf8Kirp(char* m) {
  int n = strlen(m);
  int i = n - 1;
  while (i >= 0 && ((uint8_t)m[i] & 0xC0) == 0x80) i--;          // devam baytlari
  if (i < 0) { m[0] = 0; return; }
  uint8_t b = m[i];
  int gerek = b >= 0xF0 ? 4 : b >= 0xE0 ? 3 : b >= 0xC0 ? 2 : 1;
  if (n - i < gerek) m[i] = 0;
}

// Bildirimin uygulama adini ve basligini (arayan kisi) ister
void ancsBilgiIste(uint32_t uid, uint8_t tur, uint8_t kat) {
  if (!ancsKontrol || ancsBaglanti == BAGLANTI_YOK) return;
  uint8_t k[] = { 0, (uint8_t)uid, (uint8_t)(uid >> 8), (uint8_t)(uid >> 16), (uint8_t)(uid >> 24),
                  0,          // uygulama kimligi
                  1, 48, 0 }; // baslik, en fazla 48 bayt
  portENTER_CRITICAL(&ancsKilit);
  ancsVeriUz = 0;
  portEXIT_CRITICAL(&ancsKilit);
  ancsIstenenUid = uid; ancsIstenenTur = tur; ancsIstenenKat = kat; ancsIstekZamani = millis();
  if (!ancsKontrol->writeValue(k, sizeof(k), true)) {
    Kayit.println("Arama bilgisi iPhone'dan istenemedi.");
    ancsIstenenTur = 0;
  }
}

void aramaBitti() {
  if (!aramaVar) return;
  aramaVar = false;
  Kayit.println("Arama ekrani kapandi (cevaplandi, reddedildi veya arayan vazgecti).");
  durumYayinla();
}

// Bildirim olayi: [olay][bayraklar][kategori][sayi][uid 4 bayt]
void ancsOlayIsle(const uint8_t* b) {
  uint8_t olay = b[0], bayrak = b[1], kat = b[2];
  uint32_t uid = b[4] | (b[5] << 8) | ((uint32_t)b[6] << 16) | ((uint32_t)b[7] << 24);
  bool onceden = bayrak & 0x04;                        // abone olmadan once vardi
  if (kat == 1) {                                      // gelen arama
    if (olay == 2) { if (aramaVar && uid == aramaUid) aramaBitti(); return; }
    if (aramaVar && uid == aramaUid) return;           // ayni arama, degisiklik
    aramaVar = true; aramaBildirildi = false;
    aramaUid = uid; aramaBasi = millis();
    aramaKim[0] = 0; strcpy(aramaUyg, "?");
    ancsBilgiIste(uid, 1, kat);
    durumYayinla();
  } else if (kat == 2 && olay == 0 && !onceden) {     // cevapsiz arama
    ancsBilgiIste(uid, 2, kat);
  } else if (olay == 0 && !onceden && (bayrak & 0x08) && (bayrak & 0x10)) {
    ancsBilgiIste(uid, 3, kat);                        // test: kabul/ret secenekli baska bildirim
  }
}

void aramaBilgisiTamam(const char* uyg, const char* baslik) {
  if (ancsIstenenTur == 1) {
    if (aramaVar && ancsIstenenUid == aramaUid) {
      strlcpy(aramaKim, baslik[0] ? baslik : "bilinmeyen", sizeof(aramaKim)); utf8Kirp(aramaKim);
      strlcpy(aramaUyg, uyg[0] ? uygulamaAdi(uyg) : "?", sizeof(aramaUyg));
      aramaBildirildi = true;
      Kayit.printf("Gelen arama: %s (%s). Kabul: deklansor tek basis / Serial a, ret: cift basis / Serial n\n", aramaKim, aramaUyg);
      if (strcmp(aramaUyg, "Telefon") && strcmp(aramaUyg, "WhatsApp") && strcmp(aramaUyg, "FaceTime")) Kayit.printf("  (uygulama kimligi: %s)\n", uyg);
      durumYayinla();
    }
  } else if (ancsIstenenTur == 2) {
    char kim[64]; strlcpy(kim, baslik, sizeof(kim)); utf8Kirp(kim);
    Kayit.printf("Cevapsiz arama: %s (%s)\n", kim[0] ? kim : "bilinmeyen", uyg[0] ? uygulamaAdi(uyg) : "?");
  } else if (ancsIstenenTur == 3) {
    Kayit.printf("Bilgi: kabul/ret secenekli bildirim (kategori %d, uygulama %s)\n", ancsIstenenKat, uyg[0] ? uyg : "?");
  }
  ancsIstenenTur = 0;
}

// Istenen bilgilerin cevabi: [0][uid 4][kimlik][uzunluk 2][veri]... parcalar halinde gelebilir
void ancsVeriIsle() {
  if (!ancsIstenenTur) return;
  static uint8_t t[600];
  uint16_t n;
  portENTER_CRITICAL(&ancsKilit);
  n = ancsVeriUz;
  memcpy(t, ancsVeri, n);
  portEXIT_CRITICAL(&ancsKilit);
  bool zamanAsimi = millis() - ancsIstekZamani > 2500;
  char uyg[64] = "", baslik[64] = "";
  int bulunan = 0;
  if (n >= 5 && t[0] == 0) {
    uint32_t uid = t[1] | (t[2] << 8) | ((uint32_t)t[3] << 16) | ((uint32_t)t[4] << 24);
    if (uid == ancsIstenenUid) {
      size_t i = 5;
      while (i + 3 <= n && bulunan < 2) {
        uint8_t kimlik = t[i];
        uint16_t uz = t[i + 1] | (t[i + 2] << 8);
        if (i + 3 + uz > n) break;                       // gerisi henuz gelmedi
        char* hedef = kimlik == 0 ? uyg : (kimlik == 1 ? baslik : nullptr);
        if (hedef) { size_t k = min((size_t)uz, (size_t)63); memcpy(hedef, t + i + 3, k); hedef[k] = 0; }
        i += 3 + uz;
        bulunan++;
      }
    }
  }
  if (bulunan >= 2 || zamanAsimi) {
    if (bulunan < 2 && ancsIstenenTur == 1) Kayit.println("Arama bilgisi tam alinamadi.");
    aramaBilgisiTamam(uyg, baslik);
  }
}

// Kabul: iPhone'a "olumlu eylem", ret: "olumsuz eylem"
void aramaCevapla(bool kabul, const char* kimden) {
  if (!aramaVar) { Kayit.println("Su an gelen arama yok."); return; }
  if (!ancsKontrol || ancsBaglanti == BAGLANTI_YOK) { Kayit.println("iPhone bildirim servisi hazir degil, arama cevaplanamadi."); return; }
  uint8_t k[] = { 2, (uint8_t)aramaUid, (uint8_t)(aramaUid >> 8), (uint8_t)(aramaUid >> 16), (uint8_t)(aramaUid >> 24),
                  (uint8_t)(kabul ? 0 : 1) };
  bool ok = ancsKontrol->writeValue(k, sizeof(k), true);
  Kayit.printf("Arama %s (%s)%s\n", kabul ? "KABUL ediliyor" : "REDDEDILIYOR", kimden,
               ok ? "" : " - iPhone istegi kabul etmedi");
}

// loop icinden
void ancsIsle() {
  if (ancsBaglanti != BAGLANTI_YOK && baglantiBul(false) != ancsBaglanti) {
    Kayit.println("iPhone baglantisi degisti: arama dinleme durdu.");
    ancsSifirla();
    durumYayinla();
  }
  if (ancsBaglanti == BAGLANTI_YOK && ancsKurZamani == 0 && ancsDeneme == 0 && iphoneBagli()) ancsKurZamani = millis() + 3000;
  if (ancsKurZamani && (int32_t)(millis() - ancsKurZamani) >= 0) ancsKur();
  while (true) {
    uint8_t b[8];
    portENTER_CRITICAL(&ancsKilit);
    bool bos = ancsOlayBas == ancsOlaySon;
    if (!bos) { memcpy(b, ancsOlaylar[ancsOlayBas], 8); ancsOlayBas = (ancsOlayBas + 1) % ANCS_OLAY; }
    portEXIT_CRITICAL(&ancsKilit);
    if (bos) break;
    ancsOlayIsle(b);
  }
  ancsVeriIsle();
  if (aramaVar && !aramaBildirildi && millis() - aramaBasi > 3000) {
    aramaBildirildi = true;
    Kayit.println("Gelen arama (kim oldugu alinamadi). Kabul: deklansor tek basis / Serial a, ret: cift basis / Serial n");
  }
  if (aramaVar && millis() - aramaBasi > 120000) {     // guvenlik: kapanis haberi gelmediyse
    Kayit.println("Arama 2 dakikadir acik gorunuyordu, kapali sayildi.");
    aramaBitti();
  }
}

class SunucuTakip : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& c) override {
    Kayit.printf("Bluetooth: cihaz baglandi (%s)\n", c.getAddress().toString().c_str());
  }
  void onDisconnect(NimBLEServer* s, NimBLEConnInfo& c, int sebep) override {
    String a = c.getIdAddress().toString().c_str();
    Kayit.printf("Bluetooth: %s ayrildi (kod 0x%02X)\n", (pcAdres.length() && a == pcAdres) ? "bilgisayar" : "iPhone", sebep & 0xFF);
  }
  void onAuthenticationComplete(NimBLEConnInfo& c) override {
    if (!c.isEncrypted()) { Kayit.println("Bluetooth: sifreli baglanti kurulamadi."); return; }
    String a = c.getIdAddress().toString().c_str();
    if (pcEslesmeAcik() && a != ipAdres) {
      pcAdres = a; pcAdresTipi = c.getIdAddress().getType(); pcEslesmeBitis = 0;
      hafiza.putString("pcAdr", pcAdres); hafiza.putUChar("pcTip", pcAdresTipi);
      Kayit.printf("Bilgisayar tanitildi ve kaydedildi (%s).\n", a.c_str());
    } else if (a != pcAdres && a != ipAdres) {
      if (ipAdres.length() == 0) {
        ipAdres = a;
        hafiza.putString("ipAdr", ipAdres);
        Kayit.printf("iPhone tanindi ve kaydedildi (%s).\n", a.c_str());
      } else {
        Kayit.printf("Taninmayan cihaz baglandi (%s). Bilgisayarsa: sayfada once 'Bilgisayari eslestir'e basin, Windows'ta cihazi kaldirip yeniden ekleyin.\n", a.c_str());
      }
    } else {
      Kayit.printf("Bluetooth: %s yeniden baglandi.\n", a == pcAdres ? "bilgisayar" : "iPhone");
    }
    if (a == ipAdres) { ancsDeneme = 0; ancsKurZamani = millis() + 2000; }   // gelen arama dinlemesini kur (ana dongude)
  }
};
SunucuTakip sunucuTakip;

void medyaTusu(uint16_t kod) {
  uint16_t h = baglantiBul(false);
  if (h == BAGLANTI_YOK || !medyaRaporu) { Kayit.println("iPhone bagli degil, tus gonderilemedi."); return; }
  uint8_t bas[2] = { (uint8_t)(kod & 0xFF), (uint8_t)(kod >> 8) };
  uint8_t birak[2] = { 0, 0 };
  medyaRaporu->notify(bas, 2, h);
  delay(40);
  medyaRaporu->notify(birak, 2, h);
}

// ---------------------------------------------------------------------
//  IMLEC KONUMLANDIRMA (AssistiveTouch hareketleri imlecin oldugu yerde oynatilir)
// ---------------------------------------------------------------------
// Hedefler bir koseye gore saklanir: kamera simgesi sag alt koseye, zoom noktasi sol ust koseye gore
int16_t kameraHedefX = 60, kameraHedefY = 150;   // kamera cevirme simgesi, sag alt koseye gore (fare birimi)
int16_t zoomHedefX = 150, zoomHedefY = 300;      // ekranin ortasi, sol ust koseye gore
int16_t gorunumHedefX = 40, gorunumHedefY = 60;  // kucuk pencere, sag alt koseye gore
int16_t mikrofonHedefX = 120, mikrofonHedefY = 40;  // menudeki mikrofon dugmesi, sag alt koseye gore
int16_t uyandirHedefX = 40, uyandirHedefY = 200;    // menuyu acmak icin ana goruntu uzerinde bos nokta, sag alt koseye gore
// kalibrasyon hedefleri: 1 kamera, 2 ekran ortasi, 3 kucuk pencere, 4 mikrofon, 6 uyandirma noktasi
uint8_t kalibHedef = 0;
int16_t kalX = 0, kalY = 0;
const uint32_t FARE_ADIM_MS = 15;

void fareRaporGonder(uint8_t dugme, int8_t dx, int8_t dy) {
  uint8_t r[4] = { dugme, (uint8_t)dx, (uint8_t)dy, 0 };
  uint16_t h = baglantiBul(false);
  if (h != BAGLANTI_YOK) fareRaporu->notify(r, 4, h);
}

// Imleci (dx,dy) kadar kucuk ve esit adimlarla kaydirir
void imleciKaydir(int16_t dx, int16_t dy) {
  while (dx != 0 || dy != 0) {
    int8_t sx = dx > 6 ? 6 : (dx < -6 ? -6 : dx);
    int8_t sy = dy > 6 ? 6 : (dy < -6 ? -6 : dy);
    fareRaporGonder(0, sx, sy);
    dx -= sx; dy -= sy;
    delay(FARE_ADIM_MS);
  }
}

// Koseye itmek icin gereken adim sayisi: ekran boyutu zoom noktasindan (ekranin ortasi) tahmin edilir
int koseAdimSayisi() {
  int enBuyuk = max(zoomHedefX, zoomHedefY) * 2;
  int n = enBuyuk * 13 / 10 / 127 + 4;
  return constrain(n, 8, 45);
}

// Imlec bir islemin basinda BIR KEZ koseye itilir, sonra ayni islem icinde oldugu yerden hedefe gider.
// Hedefler kenardan uzakta oldugu icin bu kisa hareketlerde kayma olmaz.
uint8_t imlecKose = 0;       // 0 sol ust, 1 sag alt
int16_t imlecX = 0, imlecY = 0;

void imleceBasla(uint8_t kose) {
  int8_t yon = kose == 0 ? -127 : 127;
  int n = koseAdimSayisi();
  for (int i = 0; i < n; i++) { fareRaporGonder(0, yon, yon); delay(FARE_ADIM_MS); }
  delay(30);
  imlecKose = kose; imlecX = 0; imlecY = 0;
}

// (x, y): basta secilen koseden iceri dogru uzaklik
void imleceGit(int16_t x, int16_t y) {
  int16_t dx = x - imlecX, dy = y - imlecY;
  if (imlecKose == 0) imleciKaydir(dx, dy); else imleciKaydir(-dx, -dy);
  imlecX = x; imlecY = y;
  delay(30);
}

void imleciGotur(uint8_t kose, int16_t x, int16_t y) { imleceBasla(kose); imleceGit(x, y); }

void fareTikla(uint8_t no) {
  fareRaporGonder(1 << (no - 1), 0, 0);
  delay(40);
  fareRaporGonder(0, 0, 0);
}

bool fareHazir() {
  if (!iphoneBagli() || !fareRaporu) { Kayit.println("iPhone bagli degil."); return false; }
  return true;
}

void durumYayinla();

// Kameram kapali mi (tahmin): Button 5 (Lock Screen) ile ekran kilitlenince WhatsApp goruntuyu kapatir,
// gorusme sesli devam eder. Kart ekrani goremedigi icin bu bir tahmindir.
bool kameraKapali = false;
bool homeBekliyor = false;          // ekran uyandirildi, Face ID bekleniyor
uint32_t homeZamani = 0;
const uint32_t FACE_ID_BEKLE = 2000;

// Imlecle ekrana dokunan islemler kilit ekraninda yanlis yere dokunmasin
bool ekranKullanilabilir() {
  if (!fareHazir()) return false;
  if (aramaVar) { Kayit.println("Arama caliyor: ekrana dokunan islem yapilmadi."); return false; }
  if (kameraKapali || homeBekliyor) {
    Kayit.println("Kameram kapali sayiliyor (ekran kilitli). Once deklansore cift basin (veya 9).");
    return false;
  }
  return true;
}

// WhatsApp bir sure dokunulmayinca kontrolleri gizleyip kucuk pencereyi kucultuyor.
// Uzun sure gectiyse once ekranin ortasina (ana goruntu) dokunup uyandirir, sonra simgeye dokunur.
uint32_t sonEkranDokunusu = 0;
uint32_t uyandirmaEsik = 10000;   // bu sureden kisa zamanda tekrar basilirsa uyandirma atlanir (u ile degisir, 0 = kapali)

// WhatsApp menusunun (kontrollerin) acik olup olmadigini tahmin eder.
// Kart ekrani goremedigi icin: menu acik sayildiktan sonra "uyandirmaEsik" suresi kadar acik kalir.
bool menuAcikSayilan = false;

bool menuAcikMi() {
  return menuAcikSayilan && uyandirmaEsik > 0 && millis() - sonEkranDokunusu < uyandirmaEsik;
}

// Sag alt kose grubundaki islemler: imlec kosede baslar, menu kapali sayiliyorsa once
// uyandirma noktasina dokunur (menu acilir, kucuk pencere buyur), sonra koseye donmeden hedefe gider.
bool sagAltIslem(int16_t hedefX, int16_t hedefY) {
  imleceBasla(1);
  bool acildi = false;
  if (uyandirmaEsik > 0 && !menuAcikMi()) {
    imleceGit(uyandirHedefX, uyandirHedefY);
    fareTikla(1);
    delay(700);             // menunun ve pencerenin acilmasini bekle
    acildi = true;
  }
  imleceGit(hedefX, hedefY);
  fareTikla(1);
  sonEkranDokunusu = millis();
  menuAcikSayilan = true;
  return acildi;
}

void iphoneKameraDegistir() {
  if (!ekranKullanilabilir()) return;
  bool acildi = sagAltIslem(kameraHedefX, kameraHedefY);
  Kayit.printf("iPhone: kamera degistir%s\n", acildi ? " (once menu acildi)" : "");
}

void iphoneGorunumDegistir() {
  if (!ekranKullanilabilir()) return;
  bool acildi = sagAltIslem(gorunumHedefX, gorunumHedefY);
  Kayit.printf("iPhone: ekranlari degistir%s\n", acildi ? " (once menu acildi)" : "");
}

// Mikrofon kapali mi (tahmin): kart ekrani goremez, her mikrofon dokunusunda ters cevrilir.
// Telefona elle dokunulursa ters kalabilir; "mikDuzelt" komutu (veya Serial j) sadece tahmini cevirir.
bool mikrofonKapali = false;

void iphoneMikrofon() {
  if (!ekranKullanilabilir()) return;
  bool acildi = sagAltIslem(mikrofonHedefX, mikrofonHedefY);
  mikrofonKapali = !mikrofonKapali;
  Kayit.printf("iPhone: mikrofon %s (tahmin)%s\n", mikrofonKapali ? "KAPALI" : "ACIK", acildi ? " (once menu acildi)" : "");
  durumYayinla();
}

// Telefona dokunmadan sadece mikrofon tahminini duzeltir
void mikrofonTahminiDuzelt() {
  mikrofonKapali = !mikrofonKapali;
  Kayit.printf("Mikrofon tahmini duzeltildi: %s (telefona dokunulmadi)\n", mikrofonKapali ? "KAPALI" : "ACIK");
  durumYayinla();
}

// Kameram kapat / ac. iPhone'da AssistiveTouch: Button 5 = Lock Screen, Button 2 = Home.
// Kapat: Button 5 -> ekran kilitlenir, WhatsApp goruntuyu kapatir (ses devam eder).
// Ac: Button 5 ekrani uyandirir, Face ID icin beklenir, sonra Button 2 (Home) -> gorusmeye donulur.
void iphoneKameramKapatAc() {
  if (!fareHazir()) return;
  if (aramaVar) { Kayit.println("Arama caliyor: kameram kapat/ac yapilmadi (aramayi reddederdi)."); return; }
  if (homeBekliyor) { Kayit.println("Kamera zaten aciliyor, bekleyin."); return; }
  menuAcikSayilan = false;
  if (!kameraKapali) {
    fareTikla(5);
    kameraKapali = true;
    Kayit.println("iPhone: kameram KAPATILDI (Button 5 / ekran kilitlendi)");
  } else {
    fareTikla(5);
    homeBekliyor = true;
    homeZamani = millis();
    Kayit.println("iPhone: kameram aciliyor: ekran uyandirildi, Face ID bekleniyor...");
  }
  durumYayinla();
}

// loop icinden cagrilir: Face ID suresi dolunca Home gonderir
void kameraAcmaIsle() {
  if (!homeBekliyor || millis() - homeZamani < FACE_ID_BEKLE) return;
  homeBekliyor = false;
  if (!fareHazir()) return;
  fareTikla(2);
  kameraKapali = false;
  menuAcikSayilan = false;
  Kayit.println("iPhone: Home gonderildi, kameram ACILDI (Face ID acmadiysa ekran kilitli kalir)");
  durumYayinla();
}

// Ana goruntunun ortasina dokunur: WhatsApp kontrolleri acar/kapatir
void iphoneKontroller() {
  if (!ekranKullanilabilir()) return;
  bool acikti = menuAcikMi();
  imleciGotur(0, zoomHedefX, zoomHedefY);
  fareTikla(1);
  menuAcikSayilan = !acikti;
  sonEkranDokunusu = millis();
  Kayit.printf("iPhone: menu %s\n", menuAcikSayilan ? "acildi" : "gizlendi");
}

void iphoneZoomArti() {
  if (!ekranKullanilabilir()) return;
  imleciGotur(0, zoomHedefX, zoomHedefY);
  fareTikla(3);   // AssistiveTouch: Button 3 = Zoom + hareketi
  Kayit.println("iPhone: zoom +");
}
void iphoneZoomEksi() {
  if (!ekranKullanilabilir()) return;
  imleciGotur(0, zoomHedefX, zoomHedefY);
  fareTikla(4);   // AssistiveTouch: Button 4 = Zoom - hareketi
  Kayit.println("iPhone: zoom -");
}

uint8_t kalibKose(uint8_t hedef) { return hedef == 2 ? 0 : 1; }   // ekran ortasi sol ust, digerleri sag alt

void kalibrasyonBaslat(uint8_t hedef) {
  if (!fareHazir()) return;
  kalibHedef = hedef;
  switch (hedef) {
    case 1: kalX = kameraHedefX;   kalY = kameraHedefY;   break;
    case 2: kalX = zoomHedefX;     kalY = zoomHedefY;     break;
    case 3: kalX = gorunumHedefX;  kalY = gorunumHedefY;  break;
    case 4: kalX = mikrofonHedefX; kalY = mikrofonHedefY; break;
    default: kalibHedef = 6; kalX = uyandirHedefX; kalY = uyandirHedefY; break;
  }
  imleciGotur(kalibKose(kalibHedef), kalX, kalY);   // koseden bir kez gelir
  const char* mesaj = kalibHedef == 1 ? "imleci KAMERA CEVIRME simgesinin ustune getirin"
                    : kalibHedef == 2 ? "imleci ekranin ORTASINA getirin (zoom noktasi)"
                    : kalibHedef == 3 ? "imleci KUCUK PENCERENIN ustune getirin (buyumus halinin ortasina)"
                    : kalibHedef == 4 ? "imleci menudeki MIKROFON dugmesinin ustune getirin"
                                      : "imleci UYANDIRMA NOKTASINA getirin: sag altta, ana goruntu uzerinde, hicbir dugmeye ve kucuk pencereye denk gelmeyen bos bir yer";
  Kayit.printf("AYAR: %s\n", mesaj);
  Kayit.println("  w yukari, s asagi, a sola, d saga (buyuk adim). Shift ile W A S D ince adim.");
  Kayit.println("  v kaydet ve dogrula, q vazgec.");
}

void kalibrasyonTus(char c) {
  int16_t dx = 0, dy = 0;   // ekrandaki yon: +x saga, +y asagi
  switch (c) {
    case 'w': dy = -25; break;  case 's': dy = 25; break;
    case 'a': dx = -25; break;  case 'd': dx = 25; break;
    case 'W': dy = -5;  break;  case 'S': dy = 5;  break;
    case 'A': dx = -5;  break;  case 'D': dx = 5;  break;
    case 'v': {
      switch (kalibHedef) {
        case 1: kameraHedefX = kalX; kameraHedefY = kalY; hafiza.putShort("kx", kalX); hafiza.putShort("ky", kalY); break;
        case 2: zoomHedefX = kalX; zoomHedefY = kalY; hafiza.putShort("zx", kalX); hafiza.putShort("zy", kalY); break;
        case 3: gorunumHedefX = kalX; gorunumHedefY = kalY; hafiza.putShort("gx", kalX); hafiza.putShort("gy", kalY); break;
        case 4: mikrofonHedefX = kalX; mikrofonHedefY = kalY; hafiza.putShort("mx2", kalX); hafiza.putShort("my2", kalY); break;
        default: uyandirHedefX = kalX; uyandirHedefY = kalY; hafiza.putShort("ux", kalX); hafiza.putShort("uy", kalY); break;
      }
      uint8_t kose = kalibKose(kalibHedef);
      kalibHedef = 0;
      Kayit.println("Kaydedildi. Dogrulama: imlec koseden gelip kaydedilen noktaya gidiyor...");
      imleciGotur(kose, kalX, kalY);
      Kayit.println("Imlec dogru yerdeyse tamam. Degilse ayari tekrar baslatin.");
      return;
    }
    case 'q': kalibHedef = 0; Kayit.println("Ayar iptal edildi."); return;
    default: return;
  }
  int8_t isaret = kalibKose(kalibHedef) == 0 ? 1 : -1;   // sag alt kosede iki yon de ters
  kalX += dx * isaret;
  kalY += dy * isaret;
  if (kalX < 0) kalX = 0;
  if (kalY < 0) kalY = 0;
  imleceGit(kalX, kalY);    // koseye donmeden oldugu yerden kayar
}

// ---------------------------------------------------------------------
//  BILGISAYAR: KLAVYE VE FARE (ABD klavye duzeni)
// ---------------------------------------------------------------------
const uint8_t SHIFT = 0x02;

bool pcHazir() {
  if (!pcBagli()) { Kayit.println(pcAdres.length() ? "Bilgisayar bagli degil." : "Bilgisayar henuz tanitilmadi (sayfada: Bilgisayari eslestir)."); return false; }
  return true;
}

void pcKlavyeRaporu(uint8_t mod, uint8_t tus) {
  uint16_t h = baglantiBul(true);
  if (h == BAGLANTI_YOK || !klavyeRaporu) return;
  uint8_t r[8] = { mod, 0, tus, 0, 0, 0, 0, 0 };
  klavyeRaporu->notify(r, 8, h);
}

void pcTusBas(uint8_t mod, uint8_t tus) {
  pcKlavyeRaporu(mod, tus);
  delay(14);
  pcKlavyeRaporu(0, 0);
  delay(14);
}

// Bir karakterin ABD klavyesindeki tus kodu
bool karakterTusu(char c, uint8_t& mod, uint8_t& tus) {
  mod = 0;
  if (c >= 'a' && c <= 'z') { tus = 0x04 + (c - 'a'); return true; }
  if (c >= 'A' && c <= 'Z') { tus = 0x04 + (c - 'A'); mod = SHIFT; return true; }
  if (c >= '1' && c <= '9') { tus = 0x1E + (c - '1'); return true; }
  if (c == '0') { tus = 0x27; return true; }
  static const char normal[] = " -=[]\\;'`,./";
  static const uint8_t normalKod[] = { 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38 };
  static const char shiftli[] = "!@#$%^&*()_+{}|:\"~<>?";
  static const uint8_t shiftliKod[] = { 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38 };
  const char* p = strchr(normal, c);
  if (c && p) { tus = normalKod[p - normal]; return true; }
  p = strchr(shiftli, c);
  if (c && p) { tus = shiftliKod[p - shiftli]; mod = SHIFT; return true; }
  if (c == '\n') { tus = 0x28; return true; }
  if (c == '\t') { tus = 0x2B; return true; }
  return false;
}

void pcYaz(const char* m, bool enter, int sil) {
  if (!pcHazir()) return;
  int yazilan = 0, atlanan = 0;
  for (int i = 0; i < constrain(sil, 0, 300); i++) pcTusBas(0, 0x2A);   // canli yazmada silinen harfler
  for (const char* p = m; *p && yazilan < 200; p++) {
    uint8_t mod, tus;
    if (karakterTusu(*p, mod, tus)) { pcTusBas(mod, tus); yazilan++; }
    else atlanan++;
  }
  if (enter) pcTusBas(0, 0x28);
  if (atlanan) Kayit.printf("Bilgisayar: %d karakter yazilamadi (ABD klavyesinde yok)\n", atlanan);
}

void pcOzelTus(const char* k) {
  if (!pcHazir()) return;
  struct T { const char* ad; uint8_t mod, tus; };
  static const T TUSLAR[] = {
    {"enter", 0, 0x28}, {"esc", 0, 0x29}, {"sil", 0, 0x2A}, {"tab", 0, 0x2B}, {"bosluk", 0, 0x2C},
    {"sag", 0, 0x4F}, {"sol", 0, 0x50}, {"asagi", 0, 0x51}, {"yukari", 0, 0x52}, {"f", 0, 0x09},
    {"sekmeKapat", 0x01, 0x1A}, {"alttab", 0x04, 0x2B}, {"win", 0x08, 0}
  };
  for (const T& t : TUSLAR) if (!strcmp(t.ad, k)) { pcTusBas(t.mod, t.tus); return; }
  Kayit.printf("Bilinmeyen bilgisayar tusu: %s\n", k);
}

void pcMedya(const char* k) {
  if (!pcHazir()) return;
  uint16_t kod = !strcmp(k, "sesArti") ? 0xE9 : !strcmp(k, "sesEksi") ? 0xEA : !strcmp(k, "sessiz") ? 0xE2 : !strcmp(k, "oynat") ? 0xCD : 0;
  if (!kod) return;
  uint16_t h = baglantiBul(true);
  uint8_t bas[2] = { (uint8_t)(kod & 0xFF), (uint8_t)(kod >> 8) }, birak[2] = { 0, 0 };
  medyaRaporu->notify(bas, 2, h);
  delay(30);
  medyaRaporu->notify(birak, 2, h);
}

void pcFareRaporu(uint8_t dugme, int8_t dx, int8_t dy, int8_t teker) {
  uint16_t h = baglantiBul(true);
  if (h == BAGLANTI_YOK) return;
  uint8_t r[4] = { dugme, (uint8_t)dx, (uint8_t)dy, (uint8_t)teker };
  fareRaporu->notify(r, 4, h);
}

uint8_t pcBasiliDugme = 0;   // surukleme icin basili tutulan dugme

void pcFare(int dx, int dy, int teker) {
  if (!pcBagli()) return;
  dx = constrain(dx, -2000, 2000); dy = constrain(dy, -2000, 2000); teker = constrain(teker, -30, 30);
  while (dx || dy || teker) {
    int8_t sx = constrain(dx, -127, 127), sy = constrain(dy, -127, 127), st = constrain(teker, -10, 10);
    pcFareRaporu(pcBasiliDugme, sx, sy, st);
    dx -= sx; dy -= sy; teker -= st;
    delay(8);
  }
}

void pcTikla(uint8_t dugme, bool cift) {
  if (!pcHazir()) return;
  for (int i = 0; i < (cift ? 2 : 1); i++) {
    pcFareRaporu(dugme, 0, 0, 0); delay(30);
    pcFareRaporu(0, 0, 0, 0);     delay(60);
  }
}

void iphoneFoto() {
  if (aramaVar) { Kayit.println("Arama caliyor: foto (ses tusu) gonderilmedi."); return; }
  Kayit.println("iPhone: fotograf (ses ac)");
  medyaTusu(0x00E9);  // Volume Up -> Kamera uygulamasinda deklansor
}

void iphoneBaslat() {
  hidSunucu = NimBLEDevice::createServer();
  hidSunucu->setCallbacks(&sunucuTakip, false);
  hid = new NimBLEHIDDevice(hidSunucu);
  medyaRaporu = hid->getInputReport(1);
  fareRaporu = hid->getInputReport(2);
  klavyeRaporu = hid->getInputReport(3);
  hid->getOutputReport(3);   // Caps Lock vb. isiklari (bilgisayar yazar, kullanilmiyor)
  hid->setManufacturer("Stand");
  hid->setPnp(0x02, 0x05AC, 0x820A, 0x0210);
  hid->setHidInfo(0x00, 0x01);
  hid->setReportMap((uint8_t*)RAPOR_HARITASI, sizeof(RAPOR_HARITASI));
  hid->setBatteryLevel(100);
  hid->startServices();

  // Yayin: HID + iPhone'dan bildirim servisi istegi (ANCS). Yer yetmedigi icin ad tarama cevabinda.
  NimBLEAdvertising* reklam = NimBLEDevice::getAdvertising();
  NimBLEAdvertisementData yayin, cevap;
  yayin.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  yayin.setAppearance(0x0180);    // genel HID / uzaktan kumanda
  yayin.addServiceUUID(hid->getHidService()->getUUID());
  NimBLEUUID ancsUuid(ANCS_SERVIS);
  const uint8_t* u = ancsUuid.getValue();
  uint8_t istek[18] = { 17, 0x15 };   // 0x15: istenen 128 bit servisler
  memcpy(istek + 2, u, 16);
  if (!yayin.addData(istek, sizeof(istek))) Kayit.println("Uyari: bildirim servisi istegi yayina sigmadi.");
  cevap.setName("Stand Kumanda");
  reklam->enableScanResponse(true);
  reklam->setAdvertisementData(yayin);
  reklam->setScanResponseData(cevap);
  NimBLEDevice::setCustomGapHandler(ancsGapOlayi);
  reklam->start();
  Kayit.println("iPhone icin yayin basladi: Ayarlar > Bluetooth > 'Stand Kumanda'");
}

// ---------------------------------------------------------------------
//  KUMANDA KOPRUSU
// ---------------------------------------------------------------------
void kumandaBildirim(NimBLERemoteCharacteristic* c, uint8_t* d, size_t n, bool bildirim) {
  if (n != 14) return;
  memcpy(kumandaTampon, d, 14);
  kumandaYeni = true;
}

// deklansor: tek basis foto, cift basis kontroller
bool deklansorBekleyen = false;
bool deklansorArama = false;   // ilk basis arama calarken mi oldu
uint32_t deklansorBasis = 0;
const uint32_t CIFT_BASIS_MS = 600;

int8_t kumYonu(uint16_t v) { return v > ORTA + 80 ? 1 : (v < ORTA - 80 ? -1 : 0); }

void akilliYarimTur() {
  if (hareketBaslat(yarimTurYonu, 0, YARIM_TUR)) {
    Kayit.printf("Kumanda 180: %s\n", yarimTurYonu > 0 ? "saga" : "sola");
    yarimTurYonu = -yarimTurYonu;
  }
}

// Standin kendi hizli 180'i (her zaman ayni yone, yerli180Yonu)
void yerli180() {
  paketGonder(ORTA, ORTA, 0, 0x02);
  yerliBitis = millis() + (uint32_t)(180.0 / DONUS_HIZI * 1000);
  if (!yerliBitis) yerliBitis = 1;
  sayacEkle(yerli180Yonu, 0, (uint32_t)(180.0 / DONUS_HIZI * 1000));
}

// Uzun bir hareket (180, konum, kucuk adim) suruyor mu? Takip adimlari ve joystick sayilmaz.
bool donuyorMu() {
  if (hr.aktif && !hr.ayri) return true;
  if (yerliBitis && (int32_t)(millis() - yerliBitis) < 0) return true;
  yerliBitis = 0;
  return false;
}

// 180 saga da sola da ayni yere varir. Sinira yaklasildiysa kablo acilan yonden yapilir.
void yuzSeksen() {
  if (mod180 == 1) {
    if (sinirAsar(yarimTurYonu, 180)) { yarimTurYonu = -yarimTurYonu; Kayit.println("Donus siniri: 180 ters yonden yapiliyor"); }
    akilliYarimTur();
    return;
  }
  bool gidis = (mod180 == 0 || !hibritGidildi);
  int8_t yon = gidis ? yerli180Yonu : -yerli180Yonu;
  if (sinirAsar(yon, 180)) { gidis = !gidis; Kayit.println("Donus siniri: 180 ters yonden yapiliyor"); }
  if (gidis) {
    yerli180();
    if (mod180 == 2) hibritGidildi = true;
    Kayit.println("180: standin kendi hareketi (gidis)");
    return;
  }
  // kartin donusu: ters yone tam hizla
  if (hareketBaslat(-yerli180Yonu, 0, geriDonusMs)) {
    hr.tam = true;
    hibritGidildi = false;
    Kayit.printf("180: kartin donusu (%lu ms)\n", (unsigned long)geriDonusMs);
  }
}

// Sayfadaki Sag/Sol 180: standin kendi 180 yonundeki dugme hizli (standin kendi hareketi),
// diger dugme kartin yavas hareketi. Sinira yaklasildiysa ters yonden yapilir.
void sayfa180(int8_t yon) {
  if (hr.aktif) { Kayit.println("Mesgul."); return; }
  if (sinirAsar(yon, 180)) { yon = -yon; Kayit.println("Donus siniri: 180 ters yonden yapiliyor"); }
  if (mod180 != 1 && yon == yerli180Yonu) {
    yerli180();
    if (mod180 == 2) hibritGidildi = true;
    Kayit.printf("Sayfa 180 %s: standin kendi hizli hareketi\n", yon > 0 ? "sag" : "sol");
    return;
  }
  if (hareketBaslat(yon, 0, YARIM_TUR)) {
    if (mod180 == 2) hibritGidildi = false;
    Kayit.printf("Sayfa 180 %s: kartin yavas hareketi\n", yon > 0 ? "sag" : "sol");
  }
}

void kumandaIsle() {
  if (!kumandaYeni) return;
  uint8_t p[14];
  memcpy(p, kumandaTampon, 14);
  kumandaYeni = false;

  if (p[0] != 0x55 || p[1] != 0x0E) return;
  uint16_t c = crc16(p, 12);
  if (p[12] != (c >> 8) || p[13] != (c & 0xFF)) return;

  uint16_t x = p[7] | (p[8] << 8);
  uint16_t y = p[9] | (p[10] << 8);
  uint8_t kamera = p[5], standTus = p[11];

  // joystick: bir sonraki bos paket gelene kadar hareket devam eder
  if (kumTersX) x = (x <= 1020) ? 1020 - x : 0;
  if (kumTersY) y = (y <= 1020) ? 1020 - y : 0;
  int8_t dx = kumYonu(x), dy = kumYonu(y);
  if (dx || dy) {
    if (!kumDx && !kumDy) kumHareketBasi = millis();
    kumDx = dx; kumDy = dy;
    kumX = x; kumY = y;
    if (standBagli() && !hr.aktif) { paketGonder(dx && sinirdaMi(dx) ? ORTA : kumX, kumY); sonPaket = millis(); }   // beklemeden ilet
  } else if (kumDx || kumDy) {
    kumDx = 0; kumDy = 0;
    kumX = ORTA; kumY = ORTA;
  }

  // tuslar: sadece basildigi an (kenar) islenir
  uint8_t yeniKamera = kamera & ~oncekiKamera;
  uint8_t yeniStand  = standTus & ~oncekiStand;
  oncekiKamera = kamera;
  oncekiStand  = standTus;

  if (yeniStand & 0x02) yuzSeksen();
  if (yeniStand & 0x04) {
    paketGonder(ORTA, ORTA, 0, 0x04);   // yeni iPhone ile auto track icin standa da iletilir
    Kayit.println("Kumanda: auto track tusu -> goruntuleri degistir");
    iphoneGorunumDegistir();
  }

  if (yeniKamera) {
    // simdilik standa iletilir (yeni iPhone ile DockKit uzerinden calisir)
    paketGonder(ORTA, ORTA, yeniKamera, 0);
    if (yeniKamera & 0x01) {
      if (deklansorBekleyen && millis() - deklansorBasis < CIFT_BASIS_MS) {
        deklansorBekleyen = false;
        if (deklansorArama) {
          Kayit.println("Kumanda: deklansor cift -> aramayi reddet");
          aramaCevapla(false, "kumanda");
        } else {
          Kayit.println("Kumanda: deklansor cift -> kameram kapat/ac");
          iphoneKameramKapatAc();
        }
      } else {
        deklansorBekleyen = true;
        deklansorArama = aramaVar;
        deklansorBasis = millis();
      }
    }
    if (yeniKamera & 0x08) { Kayit.println("Kumanda: zoom -"); iphoneZoomEksi(); }
    if (yeniKamera & 0x10) { Kayit.println("Kumanda: zoom +"); iphoneZoomArti(); }
    if (yeniKamera & 0x80) { Kayit.println("Kumanda: kamera degistir"); iphoneKameraDegistir(); }
  }
}

bool kumandayaBaglan(const NimBLEAdvertisedDevice* d) {
  if (!kumandaIstemci) {
    kumandaIstemci = NimBLEDevice::createClient();
    kumandaIstemci->setClientCallbacks(&kumandaTakip, false);
    kumandaIstemci->setConnectionParams(40, 80, 0, 1000);  // 50-100 ms aralik, 10 sn tolerans (Wi-Fi'a zaman birak)
    kumandaIstemci->setConnectTimeout(5000);
  }
  kumBaglaniyor = true;
  bool ok = kumandaIstemci->connect(d);
  kumBaglaniyor = false;
  if (!ok) { Kayit.println("Kumandaya baglanilamadi."); return false; }
  if (kumandaEslesme) {
    if (kumandaIstemci->secureConnection()) Kayit.println("Kumanda ile sifreli eslesme kuruldu.");
    else Kayit.println("Kumanda eslesmeyi kabul etmedi (sifresiz devam).");
  }
  NimBLERemoteService* sv = kumandaIstemci->getService(NUS_UUID);
  NimBLERemoteCharacteristic* tx = sv ? sv->getCharacteristic(TX_UUID) : nullptr;
  if (!tx || !tx->canNotify()) {
    Kayit.println("Bu HID cihazi bizim kumanda degil.");
    kumandaIstemci->disconnect();
    return false;
  }
  if (!tx->subscribe(true, kumandaBildirim)) {
    Kayit.println("Kumanda dinlenemedi.");
    kumandaIstemci->disconnect();
    return false;
  }
  oncekiKamera = 0; oncekiStand = 0; kumDx = 0; kumDy = 0;
  kumandaRx = sv->getCharacteristic(RX_UUID);
  kumandaBaglanmaZamani = millis();
  kumAraBitis = 0;
  Kayit.printf("[%lus] Kumanda karta baglandi (kopru aktif)%s.\n", (unsigned long)(millis() / 1000), kumandaRx ? ", RX kanali var" : "");
  durumYayinla();
  return kumandaBagli();
}

// ---------------------------------------------------------------------
int8_t yonu(int v) { return v > ORTA + 60 ? 1 : (v < ORTA - 60 ? -1 : 0); }

void durumYayinla();

// tur: 'K' komut, 'V' varlik (internet kartindan kabloyla gelir)
// ---------------------------------------------------------------------
//  KARTI YENIDEN BASLATMA (sayfadan, sadece yonetici)
//  Kameram ve mikrofon tahmini hafizaya yazilir, acilista bir kez geri yuklenir.
//  Kamera izni guvenlik icin kapali baslar (normal acilis gibi).
// ---------------------------------------------------------------------
void kartiYenidenBaslat() {
  Kayit.println("Bluetooth karti yeniden baslatiliyor (sayfadan istendi)...");
  herSeyiDurdur();
  if (standBagli()) bosta();
  hafiza.putBool("ybKam", kameraKapali || homeBekliyor);
  hafiza.putBool("ybMik", mikrofonKapali);
  hafiza.putBool("ybVar", true);
  // baglantilari duzgunce kapat: iPhone baglantinin bittigini hemen anlasin
  if (hidSunucu) {
    std::vector<uint16_t> eslar = hidSunucu->getPeerDevices();
    for (uint16_t h : eslar) hidSunucu->disconnect(h);
  }
  if (kumandaBagli()) kumandaIstemci->disconnect();
  if (standBagli()) istemci->disconnect();
  Serial.flush();
  delay(600);
  ESP.restart();
}

void komutGeldi(char tur, const char* json) {
  JsonDocument d;
  if (deserializeJson(d, json)) return;

  if (tur == 'V') {
    const char* id = d["id"] | "";
    const char* cihaz = d["cihaz"] | "bilinmeyen";
    int bos = -1, bulunan = -1;
    for (int i = 0; i < 6; i++) {
      if (kisiler[i].zaman && strcmp(kisiler[i].id, id) == 0) bulunan = i;
      if (!kisiler[i].zaman && bos < 0) bos = i;
    }
    int yer = bulunan >= 0 ? bulunan : bos;
    if (yer >= 0) {
      if (bulunan < 0) Kayit.printf("Baglandi: %s\n", cihaz);
      strlcpy(kisiler[yer].id, id, sizeof(kisiler[yer].id));
      strlcpy(kisiler[yer].cihaz, cihaz, sizeof(kisiler[yer].cihaz));
      kisiler[yer].zaman = millis();
    }
    return;
  }
  if (tur != 'K') return;

  const char* t = d["t"] | "";
  bool yonetici = strcmp(d["yk"] | "", YONETICI_KODU) == 0;

  if (!strcmp(t, "izin")) {        // eski sayfa: izin artik yok, uzaktan kontrol hep acik
    Kayit.println("Izin ayari artik yok: uzaktan kontrol her zaman acik.");
    durumYayinla();
    return;
  }
  if (!strcmp(t, "ayar")) {
    if (!yonetici) { Kayit.println("Ayar degistirme reddedildi (yonetici kodu yok)."); return; }
    const char* ad = d["ad"] | "";
    if (!strcmp(ad, "mod180"))      { mod180 = constrain((int)(d["deger"] | 2), 0, 2); hibritGidildi = false; hafiza.putUChar("mod180", mod180); }
    else if (!strcmp(ad, "geri180")) { geriDonusMs = constrain((int)(d["deger"] | 6400), 600, 12000); hafiza.putUInt("geri180", geriDonusMs); }
    else if (!strcmp(ad, "y180"))    { yerli180Yonu = (int)(d["deger"] | 1) >= 0 ? 1 : -1; hafiza.putChar("y180", yerli180Yonu); }
    else if (!strcmp(ad, "aralik"))  { ARALIK = constrain((int)(d["deger"] | 40), 20, 200); hafiza.putUInt("aralik", ARALIK); }
    else if (!strcmp(ad, "uyandir")) { uyandirmaEsik = constrain((int)(d["deger"] | 10000), 0, 15000); hafiza.putUInt("uyandir2", uyandirmaEsik); }
    else if (!strcmp(ad, "sinir"))   { int v = d["deger"] | 540; donusSiniri = v <= 0 ? 0 : constrain(v, 180, 1440); hafiza.putUShort("sinir", (uint16_t)donusSiniri); }   // 0: sinirsiz
    else if (!strcmp(ad, "kopru"))   { kopruAcik = d["deger"] | true; hafiza.putBool("kopru2", kopruAcik); if (!kopruAcik && kumandaBagli()) kumandaIstemci->disconnect(); }
    else if (!strcmp(ad, "yerel"))   { Kayit.println("Iki kart duzeninde yerel mod yok, ayar yok sayildi."); durumYayinla(); return; }
    else { Kayit.printf("Bilinmeyen ayar: %s\n", ad); return; }
    Kayit.printf("Ayar degisti (sayfa): %s\n", ad);
    durumYayinla();
    return;
  }
  if (!strcmp(t, "kalib")) {
    if (!yonetici) { Kayit.println("Imlec ayari reddedildi (yonetici kodu yok)."); return; }
    const char* islem = d["islem"] | "";
    if (!strcmp(islem, "basla")) kalibrasyonBaslat(constrain((int)(d["hedef"] | 1), 1, 6));
    else if (kalibHedef && strlen(islem) == 1) kalibrasyonTus(islem[0]);
    durumYayinla();
    return;
  }
  if (!strcmp(t, "kumandaYon")) {
    if (!yonetici) { Kayit.println("Kumanda yonu degistirme reddedildi."); return; }
    if (!d["x"].isNull()) kumTersX = d["x"].as<bool>();
    if (!d["y"].isNull()) kumTersY = d["y"].as<bool>();
    hafiza.putBool("tersX", kumTersX);
    hafiza.putBool("tersY", kumTersY);
    Kayit.printf("Kumanda yonu: yatay %s, dikey %s\n", kumTersX ? "ters" : "normal", kumTersY ? "ters" : "normal");
    durumYayinla();
    return;
  }
  if (!strcmp(t, "kumandaAra")) {
    if (!yonetici) { Kayit.println("Kumanda arama reddedildi (yonetici kodu yok)."); return; }
    if (kumandaBagli()) Kayit.println("Kumanda zaten bagli.");
    else if (!kopruAcik) Kayit.println("Kumanda koprusu kapali (Serial k).");
    else if (!standBagli()) Kayit.println("Stand bagli degil: kumanda zaten surekli araniyor.");
    else {
      kumAraBitis = millis() + KUM_ARA_MS;
      if (!kumAraBitis) kumAraBitis = 1;
      sonStandDenemesi = 0;                    // hemen tara
      Kayit.println("Kumanda araniyor (45 sn). Kumandayi simdi acin.");
    }
    durumYayinla();
    return;
  }
  if (!strcmp(t, "yenidenBaslat")) {
    if (!yonetici) { Kayit.println("Yeniden baslatma reddedildi (yonetici kodu yok)."); return; }
    kartiYenidenBaslat();
    return;
  }
  if (!strcmp(t, "kameraIzin")) {
    if (!yonetici) { Kayit.println("Kamera izni degistirme reddedildi (yonetici kodu yok)."); return; }
    kameraIzin = d["deger"] | false;
    Kayit.printf("Kamera izni %s\n", kameraIzin ? "acildi" : "kapatildi");
    durumYayinla();
    return;
  }
  // Bilgisayar komutlari: herkes kullanabilir (eslestirme/unutma yonetici ister)
  if (!strncmp(t, "pc", 2)) {
    if (!strcmp(t, "pcEslestir")) {
      if (!yonetici) { Kayit.println("Bilgisayar eslestirme reddedildi (yonetici kodu yok)."); return; }
      pcEslesmeBitis = millis() + PC_ESLESME_SURE;
      NimBLEDevice::getAdvertising()->start();
      Kayit.println("Bilgisayar eslestirme modu 2 dakika acik: Windows'ta Bluetooth > Cihaz ekle > 'Stand Kumanda'.");
    } else if (!strcmp(t, "pcUnut")) {
      if (!yonetici) { Kayit.println("Bilgisayari unutma reddedildi (yonetici kodu yok)."); return; }
      uint16_t h = baglantiBul(true);
      if (h != BAGLANTI_YOK) hidSunucu->disconnect(h);
      if (pcAdres.length()) NimBLEDevice::deleteBond(NimBLEAddress(std::string(pcAdres.c_str()), pcAdresTipi));
      pcAdres = ""; hafiza.remove("pcAdr");
      Kayit.println("Bilgisayar unutuldu. Windows'ta da 'Stand Kumanda'yi kaldirin.");
    }
    else if (!strcmp(t, "pcYaz"))   pcYaz(d["m"] | "", d["enter"] | false, d["sil"] | 0);
    else if (!strcmp(t, "pcKombo")) { if (pcHazir()) pcTusBas((uint8_t)(d["m"] | 0), (uint8_t)constrain((int)(d["k"] | 0), 0, 0xE7)); }
    else if (!strcmp(t, "pcTus"))   pcOzelTus(d["k"] | "");
    else if (!strcmp(t, "pcMedya")) pcMedya(d["k"] | "");
    else if (!strcmp(t, "pcFare"))  pcFare(d["x"] | 0, d["y"] | 0, d["w"] | 0);
    else if (!strcmp(t, "pcTik"))   pcTikla((d["b"] | 1) == 2 ? 2 : 1, d["cift"] | false);
    else if (!strcmp(t, "pcBasili")) { pcBasiliDugme = (d["deger"] | false) ? 1 : 0; if (pcBagli()) pcFareRaporu(pcBasiliDugme, 0, 0, 0); }
    // Durum sadece eslestirme/unutmada gonderilir: her fare hareketinden sonra gondermek kabloyu tikiyordu
    if (!strcmp(t, "pcEslestir") || !strcmp(t, "pcUnut")) durumYayinla();
    return;
  }
  if (!strcmp(t, "aramaKabul") || !strcmp(t, "aramaRed")) {
    if (!yonetici) { Kayit.println("Arama cevaplama reddedildi (yonetici kodu yok)."); return; }
    aramaCevapla(!strcmp(t, "aramaKabul"), "sayfa");
    return;
  }
  bool iphoneKomutu = !strcmp(t, "foto") || !strcmp(t, "zoomArti") || !strcmp(t, "zoomEksi") ||
                      !strcmp(t, "kamera") || !strcmp(t, "gorunum") || !strcmp(t, "kontroller") ||
                      !strcmp(t, "mikrofon") || !strcmp(t, "kapat") || !strcmp(t, "mikDuzelt");
  if (iphoneKomutu) {
    if ((!strcmp(t, "gorunum") || !strcmp(t, "kontroller")) && !yonetici) { Kayit.printf("iPhone komutu reddedildi (%s): yonetici kodu yok\n", t); return; }
    if (!kameraIzin && !yonetici) { Kayit.printf("iPhone komutu reddedildi (%s): kamera izni kapali\n", t); return; }
    if (!strcmp(t, "foto"))            iphoneFoto();
    else if (!strcmp(t, "zoomArti"))   iphoneZoomArti();
    else if (!strcmp(t, "zoomEksi"))   iphoneZoomEksi();
    else if (!strcmp(t, "kamera"))     iphoneKameraDegistir();
    else if (!strcmp(t, "gorunum"))    iphoneGorunumDegistir();
    else if (!strcmp(t, "kontroller")) iphoneKontroller();
    else if (!strcmp(t, "mikrofon"))   iphoneMikrofon();
    else if (!strcmp(t, "kapat"))      iphoneKameramKapatAc();
    else if (!strcmp(t, "mikDuzelt"))  mikrofonTahminiDuzelt();
    return;
  }
  if (!strcmp(t, "sifirla") && !yonetici) { Kayit.println("Baslangic yap reddedildi (yonetici kodu yok)."); return; }

  // takip adimi surerken baska bir komut gelirse (joystick, dugme, dur) adim hemen biter, o komut gecerli olur
  if (hr.aktif && hr.ayri && strcmp(t, "adim") != 0) { adimBitir(millis()); durdurmaDarbesi(); }

  if (!strcmp(t, "adim")) {
    takipAdimi((int8_t)constrain((int)(d["x"] | 0), -1, 1), (int8_t)constrain((int)(d["y"] | 0), -1, 1),
               (uint32_t)constrain((int)(d["mx"] | 0), 0, 1500), (uint32_t)constrain((int)(d["my"] | 0), 0, 1500));
  }
  else if (!strcmp(t, "joy")) {
    joyDx = yonu(d["x"] | (int)ORTA);
    joyDy = yonu(d["y"] | (int)ORTA);
    joyZaman = millis();
  }
  else if (!strcmp(t, "sag"))   sayfa180( 1);
  else if (!strcmp(t, "sol"))   sayfa180(-1);
  else if (!strcmp(t, "k180")) {   // saat kumandasi: fiziksel kumandanin 180 tusunun aynisi
    if (donuyorMu()) Kayit.println("Mesgul.");
    else { Kayit.println("Sayfadan kumanda 180'i (saat)"); yuzSeksen(); }
  }
  else if (!strcmp(t, "azsag")) hareketBaslat( 1, 0, 400);
  else if (!strcmp(t, "azsol")) hareketBaslat(-1, 0, 400);
  else if (!strcmp(t, "azyuk")) hareketBaslat( 0, 1, 350);
  else if (!strcmp(t, "azasa")) hareketBaslat( 0,-1, 350);
  else if (!strcmp(t, "ev"))    hedefeGit(0, true, 0);
  else if (!strcmp(t, "konum")) hedefeGit(d["derece"] | 0.0f, !d["tilt"].isNull(), d["tilt"] | 0.0f);
  else if (!strcmp(t, "dur"))   herSeyiDurdur();
  else if (!strcmp(t, "sifirla")) { netDonus = 0; netEgme = 0; Kayit.println("Baslangic ayarlandi."); durumYayinla(); }
}

void durumYayinla() {
  if (gAktif) return;
  JsonDocument d;
  d["stand"] = standBagli();
  d["izin"] = true;   // izin sarti kalkti; eski sayfalar icin hep acik
  d["donus"] = (int)round(netDonus);
  d["tilt"] = (int)round(netEgme);
  d["cihaz"] = kumandaBagli() ? "ESP32 · kumanda" : "ESP32 köprü";
  d["surum"] = surum();
  d["sinirde"] = sinirdeZaman && millis() - sinirdeZaman < 3000;
  sonDonuyor = donuyorMu();
  d["donuyor"] = sonDonuyor;
  d["sira180"] = (mod180 == 2 && hibritGidildi) ? "donus" : "gidis";
  d["kumanda"] = kumandaBagli();
  if (kumAramaAcik()) d["kumAra"] = (int)((kumAraBitis - millis() + 999) / 1000);
  d["iphone"] = iphoneBagli();
  d["pc"] = pcBagli();
  d["pcKayitli"] = pcAdres.length() > 0;
  if (pcEslesmeAcik()) d["pcEslesme"] = (int)((pcEslesmeBitis - millis()) / 1000);
  d["kameraIzin"] = kameraIzin;
  d["kameraKapali"] = kameraKapali || homeBekliyor;
  d["mikrofonKapali"] = mikrofonKapali;
  if (aramaVar) {
    JsonObject ar = d["arama"].to<JsonObject>();
    ar["kim"] = aramaKim;
    ar["uyg"] = aramaUyg;
  }
  d["aramaDinleme"] = ancsBaglanti != BAGLANTI_YOK;
  JsonObject a = d["ayarlar"].to<JsonObject>();
  a["mod180"] = mod180;
  a["geri180"] = geriDonusMs;
  a["y180"] = yerli180Yonu;
  a["aralik"] = ARALIK;
  a["uyandir"] = uyandirmaEsik;
  a["kopru"] = kopruAcik;
  a["sinir"] = (int)donusSiniri;
  a["yerel"] = false;
  a["kalib"] = kalibHedef;
  d["kumTersX"] = kumTersX;
  d["kumTersY"] = kumTersY;
  JsonArray u = d["uzaklar"].to<JsonArray>();
  uint32_t n = millis();
  for (int i = 0; i < 6; i++) {
    if (!kisiler[i].zaman) continue;
    if (n - kisiler[i].zaman > 9000) { Kayit.printf("Ayrildi: %s\n", kisiler[i].cihaz); kisiler[i].zaman = 0; continue; }
    JsonObject o = u.add<JsonObject>();
    o["id"] = kisiler[i].id; o["c"] = kisiler[i].cihaz;
  }
  static char tampon[1900];                       // internet kartinin tamponu 2048
  size_t n2 = serializeJson(d, tampon, sizeof(tampon));
  if (n2 == 0 || n2 >= sizeof(tampon) - 1) { Kayit.println("Durum mesaji cok buyuk, gonderilmedi."); return; }
  Serial1.write('D');
  Serial1.write((const uint8_t*)tampon, n2);
  Serial1.write('\n');
  sonDurumYayin = millis();
}

// ---------------------------------------------------------------------
//  KABLOSUZ GUNCELLEME (internet karti dosyayi kabloyla parca parca yollar)
//  Cerceve: [tur][sira][uzunluk 2 bayt][veri][CRC 2 bayt]  tur: B veri, E bitti, X iptal
//  Cevap: A tamam, N tekrar gonder, F basarisiz. Yeni program ayri bolmeye yazilir;
//  yarida kalirsa eski program calismaya devam eder.
// ---------------------------------------------------------------------
const uint32_t HIZ_NORMAL = 115200, HIZ_GUNCELLEME = 460800;
uint32_t gSonVeri = 0, gToplam = 0;
uint8_t gSonSira = 0;
bool gIlkCerceve = true;
uint8_t gCer[1600];      // gelen cerceve
size_t gCerN = 0;
uint16_t gCerUz = 0;

void guncellemeBitir(bool basarili) {
  if (!basarili && Update.isRunning()) Update.abort();
  Serial1.flush();
  Serial1.updateBaudRate(HIZ_NORMAL);
  gAktif = false;
  if (!basarili) Kayit.println("Kablosuz guncelleme iptal edildi, eski program calismaya devam ediyor.");
}

void guncellemeBasla(uint32_t boyut) {
  herSeyiDurdur();
  if (boyut < 100000 || !Update.begin(boyut)) {
    Kayit.printf("Guncelleme baslatilamadi (boyut %lu): %s\n", (unsigned long)boyut, Update.errorString());
    Serial1.print("UHATA\n");
    return;
  }
  gAktif = true; gToplam = 0; gIlkCerceve = true; gSonVeri = millis(); gCerN = 0;
  Kayit.printf("Kablosuz guncelleme basladi (%lu bayt). Lutfen bekleyin...\n", (unsigned long)boyut);
  Serial1.print("UOK\n");
  Serial1.flush();
  Serial1.updateBaudRate(HIZ_GUNCELLEME);
  delay(5);
  while (Serial1.available()) Serial1.read();
}

void guncellemeIsle() {
  uint8_t* c = gCer;
  size_t& n = gCerN;
  uint16_t& uz = gCerUz;
  while (Serial1.available()) {
    uint8_t b = Serial1.read();
    gSonVeri = millis();
    if (n < sizeof(gCer)) c[n++] = b;
    if (n == 4) {
      uz = c[2] | (c[3] << 8);
      if ((c[0] != 'B' && c[0] != 'E' && c[0] != 'X') || uz > 1500) {   // senkron kaybi: temizle, tekrar iste
        n = 0; delay(30); while (Serial1.available()) Serial1.read();
        Serial1.write('N');
        continue;
      }
    }
    if (n >= 4 && n == (size_t)uz + 6) {
      n = 0;
      uint16_t k = crc16(c + 4, uz);
      if (c[4 + uz] != (k >> 8) || c[5 + uz] != (k & 0xFF)) { Serial1.write('N'); continue; }
      if (c[0] == 'X') { guncellemeBitir(false); return; }
      if (c[0] == 'B') {
        if (!gIlkCerceve && c[1] == gSonSira) { Serial1.write('A'); continue; }   // ayni parca tekrar geldi
        if (Update.write(c + 4, uz) != uz) {
          Kayit.printf("Guncelleme yazma hatasi: %s\n", Update.errorString());
          Serial1.write('F');
          guncellemeBitir(false);
          return;
        }
        gIlkCerceve = false; gSonSira = c[1]; gToplam += uz;
        Serial1.write('A');
        continue;
      }
      // E: bitti
      if (Update.end(true)) {
        Serial1.write('A');
        Serial1.flush();
        Kayit.printf("Guncelleme tamam (%lu bayt). Kart yeniden basliyor...\n", (unsigned long)gToplam);
        delay(400);
        ESP.restart();
      }
      Kayit.printf("Guncelleme dogrulanamadi: %s\n", Update.errorString());
      Serial1.write('F');
      guncellemeBitir(false);
      return;
    }
  }
  if (millis() - gSonVeri > 8000) { Kayit.println("Guncelleme: internet kartindan veri gelmiyor."); guncellemeBitir(false); }
}

bool internetKartiVar() { return sonHatMesaji && millis() - sonHatMesaji < HAT_SESSIZ; }

// Kablodan gelen satirlari oku: K{...} komut, V{...} varlik, C1 baglanti isareti
void hatOku() {
  static char tampon[2048];
  static size_t uzunluk = 0;
  static bool tasti = false;
  while (Serial1.available()) {
    char c = Serial1.read();
    if (c == '\n') {
      tampon[uzunluk] = 0;
      if (uzunluk > 0 && !tasti) {
        bool yeniBaglandi = !internetKartiVar();
        sonHatMesaji = millis();
        if (yeniBaglandi) { Kayit.println("Internet kartiyla baglanti kuruldu."); durumYayinla(); }
        if (tampon[0] == 'K' || tampon[0] == 'V') komutGeldi(tampon[0], tampon + 1);
        else if (tampon[0] == 'U') { uzunluk = 0; guncellemeBasla(strtoul(tampon + 1, nullptr, 10)); return; }
      }
      uzunluk = 0; tasti = false;
    } else if (c != '\r') {
      if (uzunluk < sizeof(tampon) - 1) tampon[uzunluk++] = c;
      else tasti = true;                           // cok uzun satir: at
    }
  }
}

// ---------------------------------------------------------------------
// Kayit sade (19 Ekim): arama satirlari sadece aranan sey degisince yazilir, arada 10 dk'da bir ozet.
// Boylece 60 satirlik kayit "araniyor" ile dolmaz, kopma sebebi gibi onemli satirlar kalir.
uint8_t taraLazimOnce = 0;          // son yazilan arama durumu: 1 stand, 2 kumanda
uint32_t taraBolumBas = 0, taraSonOzet = 0, taraSayi = 0;
const uint32_t TARA_OZET_MS = 600000;

void cihazlariTara() {
  bool standLazim = standAra && !standBagli();
  bool kumandaLazim = kumandaAranmali();   // stand bagliyken sadece elle 45 sn
  if (!standLazim && !kumandaLazim) { taraLazimOnce = 0; return; }

  uint8_t lazim = (standLazim ? 1 : 0) | (kumandaLazim ? 2 : 0);
  bool sesli = lazim != taraLazimOnce;                // yeni arama: ayrintili yaz
  if (sesli) { taraLazimOnce = lazim; taraBolumBas = millis(); taraSonOzet = millis(); taraSayi = 0; }
  taraSayi++;
  if (sesli) Kayit.printf("Taraniyor (%s%s)...\n", standLazim ? "stand " : "", kumandaLazim ? "kumanda" : "");
  else if (millis() - taraSonOzet >= TARA_OZET_MS) {
    taraSonOzet = millis();
    Kayit.printf("Hala araniyor (%s%s): %lu dk, %lu tarama.\n", standLazim ? "stand " : "", kumandaLazim ? "kumanda" : "",
                 (unsigned long)((millis() - taraBolumBas) / 60000), (unsigned long)taraSayi);
  }
  NimBLEScan* s = NimBLEDevice::getScan();
  s->setActiveScan(true);
  bool baglantiVar = standBagli() || kumandaBagli();
  s->setInterval(160);
  s->setWindow(baglantiVar ? 48 : 120);   // baglanti varken anteni paylas
  s->setDuplicateFilter(false);
  sonTaramaKumanda = kumandaLazim;
  taramaSuruyor = true;
  NimBLEScanResults r = s->getResults(baglantiVar ? 2500 : 4000, false);
  taramaSuruyor = false;
  sonTaramaBitis = millis();

  const NimBLEAdvertisedDevice* stand = nullptr;
  const NimBLEAdvertisedDevice* kumanda = nullptr;
  for (int i = 0; i < r.getCount(); i++) {
    const NimBLEAdvertisedDevice* d = r.getDevice(i);
    std::string ad = d->getName();
    bool hidServis = d->isAdvertisingService(NimBLEUUID((uint16_t)0x1812));
    std::string uretici = d->getManufacturerData();
    bool ureticiEslesti = uretici.size() >= 2 && (uint8_t)uretici[0] == 0x52 && (uint8_t)uretici[1] == 0x31;

    // kumanda aranirken yakindaki cihazlari listele (tani icin)
    if (sesli && kumandaLazim && (hidServis || ureticiEslesti)) {
      Kayit.printf("  gorulen: '%s' %s %d dBm%s%s\n", ad.c_str(), d->getAddress().toString().c_str(),
                    d->getRSSI(), hidServis ? " [HID servisi]" : "", ureticiEslesti ? " [uretici 3152]" : "");
    }

    if (standLazim && !stand && (ad.rfind("COMITOK", 0) == 0 || d->isAdvertisingService(NimBLEUUID(NUS_UUID)))) stand = d;
    if (kumandaLazim && !kumanda && ad.rfind("COMITOK", 0) != 0 && (ad == "HID" || hidServis || ureticiEslesti)) kumanda = d;
  }

  if (kumanda) kumandayaBaglan(kumanda);

  if (stand) {
    if (!istemci) {
      istemci = NimBLEDevice::createClient();
      istemci->setClientCallbacks(&standTakip, false);
      istemci->setConnectionParams(12, 24, 0, 1000);   // 15-30 ms aralik, 10 sn tolerans (kisa anten mesguliyetinde kopmasin)
      istemci->setConnectTimeout(5000);
    }
    if (istemci->connect(stand)) {
      NimBLERemoteService* sv = istemci->getService(NUS_UUID);
      rx = sv ? sv->getCharacteristic(RX_UUID) : nullptr;
      if (rx) {
        bosta();
        sonHareket = millis();
        Kayit.println("Standa baglandi.");
        if (kopruAcik && !kumandaBagli())
          Kayit.println("Kumanda bagli degil; stand bagliyken aranmaz. Sayfadan: Ayarlar > Kumandayi ara (45 sn).");
        durumYayinla();
      } else {
        Kayit.println("Standin UART kanali yok.");
        istemci->disconnect();
      }
    } else {
      Kayit.println("Standa baglanilamadi.");
    }
  } else if (standLazim && sesli) {
    Kayit.println("Stand bulunamadi (tusla acip NFC ile uyandirin).");
  }
  if (sesli && kumandaLazim && !kumanda) Kayit.println("Kumanda bulunamadi (kapaliysa acin; standa baglandiysa standi kapatip once kumandayi acin).");
}

void yardimGoster() {
  Kayit.println();
  Kayit.println("===================== YARDIM =====================");
  Kayit.printf(" Bluetooth karti   Internet karti: %s\n", internetKartiVar() ? "bagli" : "YOK (kablolari kontrol edin)");
  Kayit.printf(" Stand: %s   Kumanda: %s   iPhone: %s   Kamera izni: %s\n",
    standBagli() ? "bagli" : "yok", kumandaBagli() ? "bagli" : "yok", iphoneBagli() ? "bagli" : "yok",
    kameraIzin ? "acik" : "kapali");
  Kayit.println("--- Kumanda ---");
  Kayit.printf(" k  kumanda koprusu (simdi: %s)\n", kopruAcik ? "acik" : "kapali");
  Kayit.printf(" x  yatay yon (simdi: %s)   y  dikey yon (simdi: %s)\n", kumTersX ? "ters" : "normal", kumTersY ? "ters" : "normal");
  Kayit.printf(" r  180 modu (simdi: %s)\n", mod180 == 0 ? "standin kendi" : (mod180 == 1 ? "akilli" : "hibrit"));
  Kayit.printf(" [ ]  hibrit donus suresi -/+ (simdi: %lu ms)   o  180 yonu (simdi: %s)\n",
    (unsigned long)geriDonusMs, yerli180Yonu > 0 ? "saga" : "sola");
  Kayit.println("--- iPhone ---");
  Kayit.println(" f  foto   3  zoom+   4  zoom-   5  kamera degistir   6  ekranlari degistir   7  menu   8  mikrofon   9  kameram kapat/ac");
  Kayit.printf(" j  mikrofon tahminini duzelt, telefona dokunmaz (simdi: %s)\n", mikrofonKapali ? "kapali" : "acik");
  Kayit.println(" e  sadece Button 2 gonder (iPhone'a Button 2'yi tanitmak icin, bir kez)");
  Kayit.printf(" a  gelen aramayi kabul et   n  reddet   (arama dinleme: %s%s)\n",
    ancsBaglanti != BAGLANTI_YOK ? "hazir" : "hazir degil", aramaVar ? ", ARAMA CALIYOR" : "");
  Kayit.println(" c  kamera simgesi   g  kucuk pencere   m  mikrofon   b  uyandirma noktasi   z  ekran ortasi (ayarlar)");
  Kayit.println("    (ayar sirasinda: w a s d buyuk adim, W A S D ince adim, v kaydet, q iptal)");
  Kayit.printf(" u  WhatsApp menu acik kalma suresi (simdi: %s sn)\n", uyandirmaEsik ? String(uyandirmaEsik / 1000).c_str() : "kapali");
  Kayit.printf(" Bilgisayar: %s%s\n", pcAdres.length() ? (pcBagli() ? "bagli" : "kayitli, bagli degil") : "tanitilmadi",
    pcEslesmeAcik() ? " (eslestirme modu acik)" : "");
  Kayit.println("--- Diger ---");
  Kayit.printf(" Donus: %d derece, sinir %s (sayfadan: Ayarlar > Kart ayarlari)   Surum: %s\n", (int)round(netDonus),
    donusSiniri > 0 ? (String("+-") + (int)donusSiniri).c_str() : "yok (sinirsiz)", surum());
  Kayit.printf(" h  web paket araligi (simdi: %lu ms)   s  dur\n", (unsigned long)ARALIK);
   Kayit.println("==================================================");
}

void isikGuncelle() {
  static uint32_t son = 0;
  if (millis() - son < 400) return;
  son = millis();
  if (!internetKartiVar()) isik(40, 0, 0);                                          // kirmizi: internet kartindan ses yok
  else if (!standBagli()) isik(0, 0, 40);                                           // mavi: stand araniyor
  else if (millis() - joyZaman < 1500 || hr.aktif) isik(30, 0, 40);                 // mor: hareket
  else if (kumandaBagli()) isik(0, 15, 15);                                         // camgobegi: kumanda bagli
  else isik(0, 12, 0);                                                              // soluk yesil: hazir
}

// =====================================================================
void setup() {
  Serial.begin(115200);
  delay(1200);
  Kayit.printf("\n=== Bluetooth karti (iki kart duzeni) · surum %s ===\n", surum());
  isik(40, 0, 0);

  Serial1.setRxBufferSize(4096);   // tarama/imlec sirasinda gelen komutlar kaybolmasin
  Serial1.setTxBufferSize(2048);
  Serial1.begin(115200, SERIAL_8N1, HAT_RX, HAT_TX);

  hafiza.begin("stand", false);
  kumTersX = hafiza.getBool("tersX", true);
  kumTersY = hafiza.getBool("tersY", false);
  kopruAcik = hafiza.getBool("kopru2", true);
  ARALIK = hafiza.getUInt("aralik", 40);
  kameraHedefX = hafiza.getShort("kx", kameraHedefX); kameraHedefY = hafiza.getShort("ky", kameraHedefY);
  zoomHedefX = hafiza.getShort("zx", zoomHedefX);     zoomHedefY = hafiza.getShort("zy", zoomHedefY);
  gorunumHedefX = hafiza.getShort("gx", gorunumHedefX); gorunumHedefY = hafiza.getShort("gy", gorunumHedefY);
  mikrofonHedefX = hafiza.getShort("mx2", mikrofonHedefX); mikrofonHedefY = hafiza.getShort("my2", mikrofonHedefY);
  uyandirHedefX = hafiza.getShort("ux", uyandirHedefX); uyandirHedefY = hafiza.getShort("uy", uyandirHedefY);
  uyandirmaEsik = hafiza.getUInt("uyandir2", 10000);
  mod180 = hafiza.getUChar("mod180", 2);
  yerli180Yonu = hafiza.getChar("y180", 1);
  geriDonusMs = hafiza.getUInt("geri180", 3600);
  donusSiniri = hafiza.getUShort("sinir", 540);
  pcAdres = hafiza.getString("pcAdr", "");
  pcAdresTipi = hafiza.getUChar("pcTip", 0);
  ipAdres = hafiza.getString("ipAdr", "");
  if (hafiza.getBool("ybVar", false)) {      // sayfadan yeniden baslatildiysa tahminleri geri yukle
    kameraKapali = hafiza.getBool("ybKam", false);
    mikrofonKapali = hafiza.getBool("ybMik", false);
    hafiza.putBool("ybVar", false);
    Kayit.printf("Sayfadan yeniden baslatildi. Kameram: %s, mikrofon: %s (onceki tahmin)\n",
      kameraKapali ? "kapali" : "acik", mikrofonKapali ? "kapali" : "acik");
  }
  Kayit.printf("Kumanda 180 modu: %s, donus %lu ms (r mod, [ ] sure, o yon)\n",
    mod180 == 0 ? "standin kendi" : (mod180 == 1 ? "akilli" : "hibrit"), (unsigned long)geriDonusMs);
  Kayit.printf("Paket araligi: %lu ms (h ile degistirin)\n", (unsigned long)ARALIK);
  if (donusSiniri > 0) Kayit.printf("Donus siniri: +-%d derece\n", (int)donusSiniri);
  else Kayit.println("Donus siniri: yok (sinirsiz)");
  Kayit.printf("Kumanda koprusu: %s (Serial Monitor'a k yazarak degistirin)\n", kopruAcik ? "acik" : "kapali");
  Kayit.printf("Kumanda yonu: yatay %s, dikey %s\n", kumTersX ? "ters" : "normal", kumTersY ? "ters" : "normal");

  NimBLEDevice::init("Stand Kumanda");
  NimBLEDevice::setSecurityAuth(true, false, true);          // eslesme (bonding) destegi
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_NO_INPUT_OUTPUT); // ekran/tus yok
  NimBLEDevice::setPower(9);                                 // en yuksek verici gucu
  iphoneBaslat();
  cihazlariTara();
  Kayit.println("Tum ayarlar ve tuslar icin ? yazip Enter'a basin.");
}

void loop() {
  uint32_t n = millis();

  if (gAktif) { guncellemeIsle(); return; }   // guncellemede baska is yapma

  hatOku();
  kayitGonder();

  if (!standBagli()) rx = nullptr;
  if (!kumandaBagli()) { kumDx = 0; kumDy = 0; }

  // stand veya kumanda eksikse periyodik tara
  uint32_t taramaAraligi = 6000;
  bool taraLazim = (standAra && !standBagli()) || kumandaAranmali();
  if (taraLazim && n - sonStandDenemesi > taramaAraligi) {
    sonStandDenemesi = millis();
    cihazlariTara();
  } else if (!taraLazim) taraLazimOnce = 0;   // aranacak bir sey yok: sonraki arama yine yazilsin
  if (kumAraBitis && !kumAramaAcik()) {       // elle arama suresi doldu
    kumAraBitis = 0;
    if (!kumandaBagli()) Kayit.println("Kumanda 45 sn'de bulunamadi. Kumandayi acip tekrar deneyin (acikken stand kapmis olabilir).");
    durumYayinla();
  }

  kumandaIsle();
  kameraAcmaIsle();
  if (deklansorBekleyen && millis() - deklansorBasis >= CIFT_BASIS_MS) {
    deklansorBekleyen = false;
    if (deklansorArama) {                        // arama calarken tek basis: kabul (mikrofon degil)
      if (aramaVar) { Kayit.println("Kumanda: deklansor tek -> aramayi kabul et"); aramaCevapla(true, "kumanda"); }
    } else iphoneMikrofon();
  }
  ancsIsle();
  if (kumandaKalp && kumandaBagli() && kumandaRx && n - sonKalp > 1000) {
    sonKalp = n;
    uint8_t b[14] = {0x55, 0x0E, 0x69, 0x13, 0x50, 0, 0, 0xFE, 0x01, 0xFE, 0x01, 0, 0, 0};
    uint16_t c = crc16(b, 12); b[12] = c >> 8; b[13] = c & 0xFF;
    kumandaRx->writeValue(b, 14, false);
  }
  if (standBagli()) {
    hareketIsle();
    if (!hr.aktif && !joyVardi && n - sonCanliTut > 3000) { bosta(); sonCanliTut = n; }
  }

  // guvenlik: iPhone bagli degilken iPhone icin yayin kapanmissa tekrar ac
  static uint32_t sonYayinKontrol = 0;
  if (n - sonYayinKontrol > 5000) {
    sonYayinKontrol = n;
    NimBLEAdvertising* reklam = NimBLEDevice::getAdvertising();
    if (pcEslesmeBitis && !pcEslesmeAcik()) { pcEslesmeBitis = 0; Kayit.println("Bilgisayar eslestirme modu kapandi."); durumYayinla(); }
    bool yayinLazim = !iphoneBagli() || (pcAdres.length() && !pcBagli()) || pcEslesmeAcik();
    if (yayinLazim && reklam && !reklam->isAdvertising()) reklam->start();
  }

  if (n - sonDurumYayin > 2000 || donuyorMu() != sonDonuyor) durumYayinla();   // donme basladi/bitti: hemen bildir
  isikGuncelle();

  // Serial Monitor komutlari (? yardim)
  if (Serial.available()) {
    char c = Serial.read();
    if (kalibHedef) { kalibrasyonTus(c); return; }
    if (c == '?') yardimGoster();
    if (c == 'c') kalibrasyonBaslat(1);
    if (c == 'z') kalibrasyonBaslat(2);
    if (c == 'g') kalibrasyonBaslat(3);
    if (c == 'm') kalibrasyonBaslat(4);
    if (c == 'b') kalibrasyonBaslat(6);
    if (c == '8') iphoneMikrofon();
    if (c == 'a') aramaCevapla(true, "Serial");
    if (c == 'n') aramaCevapla(false, "Serial");
    if (c == '9') iphoneKameramKapatAc();
    if (c == 'j') mikrofonTahminiDuzelt();
    if (c == 'e') {
      if (fareHazir()) { fareTikla(2); Kayit.println("Button 2 gonderildi. iPhone'da listeye dustuyse Home atayin."); }
    }
    if (c == '6') iphoneGorunumDegistir();
    if (c == '7') iphoneKontroller();
    if (c == 's') herSeyiDurdur();
    if (c == 'f') iphoneFoto();
    if (c == '3') iphoneZoomArti();
    if (c == '4') iphoneZoomEksi();
    if (c == '5') iphoneKameraDegistir();
    if (c == 'h') {
      static const uint32_t secenek[] = {20, 30, 40, 60, 80};
      static int i = 2;
      i = (i + 1) % 5;
      ARALIK = secenek[i];
      hafiza.putUInt("aralik", ARALIK);
      Kayit.printf("Paket araligi: %lu ms\n", (unsigned long)ARALIK);
    }
    if (c == 'k') {
      kopruAcik = !kopruAcik;
      hafiza.putBool("kopru2", kopruAcik);
      if (!kopruAcik && kumandaBagli()) kumandaIstemci->disconnect();
      Kayit.printf("Kumanda koprusu: %s\n", kopruAcik ? "acik" : "kapali");
    }
    if (c == 'u') {
      static const uint32_t secenek[] = {0, 6000, 8000, 10000, 12000};
      int i = 0; for (; i < 5; i++) if (secenek[i] == uyandirmaEsik) break;
      uyandirmaEsik = secenek[(i + 1) % 5];
      hafiza.putUInt("uyandir2", uyandirmaEsik);
      if (uyandirmaEsik) Kayit.printf("Menu acik kalma suresi: %lu sn\n", (unsigned long)(uyandirmaEsik / 1000));
      else Kayit.println("Menu uyandirma: kapali");
    }
    if (c == 'r') {
      mod180 = (mod180 + 1) % 3; hibritGidildi = false;
      hafiza.putUChar("mod180", mod180);
      Kayit.printf("Kumanda 180 modu: %s\n", mod180 == 0 ? "standin kendi" : (mod180 == 1 ? "akilli" : "hibrit"));
    }
    if (c == '[' || c == ']') {
      if (c == '[' && geriDonusMs > 600) geriDonusMs -= 200;
      if (c == ']' && geriDonusMs < 12000) geriDonusMs += 200;
      hafiza.putUInt("geri180", geriDonusMs);
      Kayit.printf("Hibrit donus suresi: %lu ms\n", (unsigned long)geriDonusMs);
    }
    if (c == 'o') {
      yerli180Yonu = -yerli180Yonu; hafiza.putChar("y180", yerli180Yonu);
      Kayit.printf("Standin 180 yonu: %s kabul edildi\n", yerli180Yonu > 0 ? "saga" : "sola");
    }
    if (c == 'y') { kumTersY = !kumTersY; hafiza.putBool("tersY", kumTersY); Kayit.printf("Kumanda dikey: %s\n", kumTersY ? "ters" : "normal"); }
    if (c == 'x') { kumTersX = !kumTersX; hafiza.putBool("tersX", kumTersX); Kayit.printf("Kumanda yatay: %s\n", kumTersX ? "ters" : "normal"); }
  }
  delay(5);
}
