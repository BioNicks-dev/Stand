// PIXEL'İ ŞİMDİ GÜNCELLE (Claude, Chrome eklentisiyle Stand sayfasında çalıştırır)
// Ne yapar: Ayarlar > Güncelleme'deki "Pixel'i şimdi güncelle" düğmesinin aynısı.
// Pixel 10 dk boyunca yarım dakikada bir yeni sürüme bakar, inince kurar.
// Uygulama dosyası (android/) değiştiğinde, derleme.sh "hazır" dedikten sonra çalıştırılır.
const kod = localStorage.getItem("stand_kod");
const c = mqtt.connect("wss://l08d16ff.ala.us-east-1.emqxsl.com:8084/mqtt", {
  clientId: "comitok_claude_" + Math.random().toString(36).slice(2, 8),
  username: "telefon", password: localStorage.getItem("stand_ss") || "",
  reconnectPeriod: 0, connectTimeout: 10000 });
await new Promise(r => { c.on('connect', r); setTimeout(r, 10000); });
const n = Date.now();
c.publish("comitok/" + kod + "/gelistirme", JSON.stringify({ bitis: n + 7200000, simdi: n }), { qos: 0, retain: true });
await new Promise(r => setTimeout(r, 1500)); c.end();
'gönderildi'
