// DURUM OKUMA (Claude, Chrome eklentisiyle Stand sayfasında çalıştırır)
// Ne yapar: ekran görüntüsü almadan sayfadan sadece birkaç satır yazı okur. En ucuz test yolu.
// Okunanlar: sayfa sürümü, Pixel uygulama sürümü, Pixel kutusu, gimbal bilgisi.
await new Promise(r => setTimeout(r, 8000));            // sayfa sunucuya bağlansın diye 8 sn bekle
const oku = id => {                                      // bir kutunun yazısını kısaltarak al
  const e = document.getElementById(id);
  return e ? e.innerText.trim().replace(/\s+/g, ' ').slice(0, 150) : null;
};
({
  sayfa:  oku('surumDamga'),   // "Sayfa: 27 Kas 01:00"
  uyg:    oku('gmPixel'),      // "uygulama v41"
  pixel:  oku('pixelKutu'),    // Pixel bağlı mı
  gimbal: oku('gbBilgi'),      // gimbal satırı
  girisEkrani: /Sunucu şifresi/.test(document.body.innerText.slice(0, 300)) // true ise giriş yapılmamış
})
