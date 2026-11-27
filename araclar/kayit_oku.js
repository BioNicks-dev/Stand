// PIXEL KAYDINI OKUMA (Claude, Chrome eklentisiyle Stand sayfasında çalıştırır)
// Ne yapar: sunucuya kısa bir bağlantı açar, Pixel'in bekçisinden son 80 kayıt satırını ister.
// Kamera sayfası uyurken ya da kapalıyken de çalışır (v46'dan sonra). Evi izle açmak gerekmez.
// Şifre sayfanın kendi hafızasından alınır; dışarı yazılmaz.
// ARA: aranacak kelime (boş bırakılırsa hepsi)
const ARA = /Gimbal/i;
const kod = localStorage.getItem("stand_kod"), satirlar = [];
const c = mqtt.connect("wss://l08d16ff.ala.us-east-1.emqxsl.com:8084/mqtt", {
  clientId: "comitok_claude_" + Math.random().toString(36).slice(2, 8),
  username: "telefon", password: localStorage.getItem("stand_ss") || "",
  reconnectPeriod: 0, connectTimeout: 10000 });
await new Promise(r => { c.on('connect', r); setTimeout(r, 10000); });
c.on('message', (k, v) => { const s = v.toString();
  if (s.indexOf('"bkayCevap"') > 0) try { JSON.parse(s).satirlar.forEach(x => satirlar.push(x)); } catch (e) {} });
c.subscribe("comitok/" + kod + "/goruntu/i");                       // cevap buraya gelir
await new Promise(r => setTimeout(r, 1500));
c.publish("comitok/" + kod + "/goruntu/k", JSON.stringify({ t: "bkay" }));   // isteği gönder
await new Promise(r => setTimeout(r, 5000)); c.end(true);
({ bagli: c.connected !== undefined, adet: satirlar.length, satirlar: satirlar.filter(x => ARA.test(x)).slice(-40) })
