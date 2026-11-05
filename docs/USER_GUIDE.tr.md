# Kullanım kılavuzu

EmotiBit Osc Mobile, EmotiBit Oscilloscope'un bilgisayarda yaptığını sahada telefondan yapar: EmotiBit'e bağlanır, kaydı başlatıp durdurur, zaman eşlemelerini yanıtlar ve canlı sinyalleri gösterir. [English guide](USER_GUIDE.md)

## 1. Bir kerelik hazırlık

### 1.1 Telefonun hotspot'u

Ayarlar → Ağ → Kişisel erişim noktası (hotspot):

- **Bant: 2.4 GHz.** EmotiBit 5 GHz ağlara bağlanamaz. Bazı telefonlarda bu seçenek "Uyumluluğu artır" ya da "Geniş uyumluluk" adını taşır.
- **Güvenlik: WPA2** (yalnız WPA3 değil).
- Ad (SSID) ve şifreyi sabit tutun.

### 1.2 EmotiBit'e hotspot'u tanıtma

EmotiBit'in SD kartını bilgisayara takın ve `config.txt` dosyasını açın. Diğer ağlarınızı silmeden hotspot'u ekleyin; EmotiBit ağları sırayla dener.

```json
{"WifiCredentials": [
  {"ssid": "TelefonHotspot", "password": "hotspot-sifresi"},
  {"ssid": "EvAgi", "password": "ev-sifresi"}
]}
```

### 1.3 Uygulamayı kurma

Telefonda <https://github.com/alparslanguvenc/Emotibit-Osc-Mobile/releases/latest/download/emotibit-osc-mobile.apk> adresini açın (her zaman en yeni sürüm) ve README'deki [Install](../README.md#install) bölümüne bakın. İlk açılışta:

1. Bildirim iznini verin. "Yeniden kapsama alanında" ve "Kayıt durduruldu" uyarıları bildirimle gelir.
2. **Kontrol** ekranında **Pil ayarlarını aç**'a dokunup uygulamayı **Kısıtlanmamış** yapın. Aksi hâlde bazı telefonlar (özellikle Samsung, Xiaomi) ekran kapalıyken arka plandaki uygulamaları durdurur.

## 2. Sahada

1. Önce **hotspot'u**, sonra EmotiBit'i açın.
2. Uygulamayı açın. EmotiBit'iniz (ör. `MD-V5-0000313`) **Cihazlar** altında görünür; **Bağlan**'a dokunun. Seçim hatırlanır; uygulama bundan sonra kendiliğinden bağlanır.
3. **Kaydı başlat**'a dokunun. Oturum kartı kırmızıya döner; dosya adı ve işleyen süre görünür.
4. Kartta **Başlangıç eşlemeleri kaydedildi** yazısını bekleyin (yaklaşık 10 sn, 2 zaman eşlemesi).
5. *(İsteğe bağlı)* **Senkron işareti:** kameranızı telefona çevirip düğmeye dokunun. Ekran beyaz yanıp söner, bip çalar ve aynı anda EmotiBit dosyasına `SYNC_MARKER_1` yazılır. Analizde bu kareyi ve bu notu, video ile biyosinyalleri hizalamak için ortak olay olarak kullanın.
6. Uzaklaşın. Bağlantının kopması beklenen bir durumdur:
   - EmotiBit SD karta **kaydetmeyi sürdürür**.
   - Uygulama aramayı sürdürür. Kapsama alanına her döndüğünüzde **kendiliğinden bağlanır** ve yeni zaman eşlemeleri toplar. Bildirimde "EmotiBit yeniden kapsama alanında" yazar.
7. **Bitirmek için: Kaydı durdur → Güvenli durdur.**
   - Güvenli durdurma önce 3 yeni zaman eşlemesi toplar (~15 sn), sonra kaydı kapatır. Böylece dosyanın sonu da eşlenir ve DataParser saat kaymasını düzeltebilir.
   - **Kapsama dışındayken** de basabilirsiniz. Uygulama bekler, cihaza ulaşınca işi bitirir ve size bildirir.
   - *Hemen durdur* yalnız acil durumlar içindir; dosyanın sonunda zaman eşlemesi olmaz.
8. *(İsteğe bağlı)* **Uyut** EmotiBit'i kapatır; yalnız kayıt yokken kullanılabilir.

**Notlar:** not alanına yazıp gönder simgesine dokunun. Notlar EmotiBit dosyasına EmotiBit saatiyle yazılır; yalnız bağlıyken gönderilebilir.

### Canlı grafik

**Canlı grafik** sekmesi Oscilloscope'taki gibi akan grafikler gösterir: EDA, PPG (kızılötesi, kırmızı ve yeşil ayrı şeritlerde), nabız, sıcaklık, ivmeölçer, jiroskop ve manyetometre.

- Panelleri düğmelerle açıp kapatın; pencereyi 5, 10, 30 ya da 60 sn seçin. Dikey eksen görünen veriye göre ölçeklenir. Her panelin sağ üstünde son değer yazar.
- Bu sekme açıkken ekran kapanmaz. **Senkron işareti** düğmesi burada da var.
- Grafik yalnız bağlıyken ve normal modda akar. Bağlantı koptuğunda grafik donar, SD karttaki kayıt sürer. Yeniden bağlanınca grafik sıfırdan başlar.
- Grafikler sinyal kalitesini kontrol etmek içindir: elektrot teması, hareket, PPG dalgası. Analizi SD karttaki dosyayla yapın.

### Pil tasarrufu: Wi-Fi kapalı (isteğe bağlı)

**Wi-Fi kapat**, EmotiBit'in Wi-Fi'ını kapatır. Kayıt sürer ve EmotiBit'in pili daha uzun dayanır, ancak:

- zaman eşlemesi kaydedilmez ve uygulama cihaza ulaşamaz;
- Wi-Fi'ı geri açmak için birinin **EmotiBit düğmesine kısa basması** gerekir. Ardından uygulama yeniden bağlanır ve güvenli durdurma yapılabilir.

Birini takip ediyorsanız Wi-Fi'ı **açık** bırakmak genellikle daha iyidir; her yaklaştığınızda uygulama ek zaman eşlemesi toplar.

## 3. Sahadan sonra

1. SD karttaki `<tarih-saat>.csv` ve `<tarih-saat>_info.json` dosyalarını kopyalayın.
2. *(İsteğe bağlı)* zaman eşlemelerini kontrol edin:
   ```bash
   python3 tools/emotibit_sim/check_sd_file.py 2026-11-05_10-15-30-123456.csv
   ```
   Komut eşleme sayısını, başta ve sonda eşleme olup olmadığını ve notları gösterir. Çıkış kodu 0 ise iki uç da eşlenmiştir.
3. Dosyayı her zamanki gibi **EmotiBit DataParser** ile ayrıştırın.
4. Uygulamanın oturum günlüğü (**Olay günlüğü → paylaş**) bağlantıları, kopmaları, notları ve her eşlemenin gidiş-dönüş süresini içerir; kaydın yanında saklamak yararlıdır. Telefonda `Android/data/io.github.alparslanguvenc.emotibitosc/files/logs/` altındadır.

## 4. Pilot deneme (önemli bir oturumdan önce)

1. **Masada 5 dk:** bağlan → başlat → 2 eşlemeyi bekle → senkron işareti → güvenli durdur. Dosyayı `check_sd_file.py` ve DataParser ile kontrol edin. DataParser "fewer than 2 timesyncs" uyarısı vermemeli.
2. **Kopma testi, 10 dk:** kayıt sırasında telefonla 2–3 dk uzaklaşıp geri gelin. Uygulamanın kendiliğinden bağlandığını, dosyada tüm süre için veri olduğunu ve `DO` (veri taşması) paketi olmadığını kontrol edin.
3. **Uzaktan güvenli durdurma:** kapsama dışındayken Güvenli durdur'a basın, sonra geri gelin. Kayıt kendiliğinden durmalı.
4. **Video hizalama:** senkron işaretini kameraya çekin; video karesi ile `SYNC_MARKER` notunun aynı anı gösterdiğini kontrol edin.

## 5. Sorun giderme

| Belirti | Olası neden / çözüm |
|---|---|
| "Wi-Fi veya hotspot ağı yok" | Hotspot kapalı. Açın; uygulama birkaç saniyede fark eder. |
| Cihaz hiç görünmüyor | Hotspot 5 GHz'de ya da WPA3'te olabilir, ya da `config.txt` içindeki ad/şifre yanlıştır. EmotiBit bağlanmışsa telefonun hotspot ekranında IP adresiyle görünür. Bu IP'yi **EmotiBit IP adresi** alanına yazıp **Yokla**'ya dokunun. |
| "başka bir host'a bağlı" | Dizüstündeki Oscilloscope hâlâ bağlı. Kapatın; cihaz ~10 sn sonra serbest kalır. |
| "Cihaz kaydı doğrulamadı" | SD kart takılı değil ya da dolu. |
| Zaman eşleme sayısı artmıyor | Bağlantı zayıf; EmotiBit yanıtı yalnız 100 ms bekler. Yaklaşın. Güvenli durdurma uzun süre başarısız olursa uyarı verir. |
| Ekran kapanınca bağlantı kopuyor | Pil ayarlarında uygulamayı **Kısıtlanmamış** yapın. |

## 6. Bilinen sınırlar

- Dosya adları ve `TL` zaman damgaları telefonun **yerel** saatinden gelir. Otomatik tarih ve saati açık tutun.
- Canlı grafiğin zaman ekseni, paket zaman damgalarından ve tahmini örnekleme hızından kurulur; yalnız görüntüleme içindir.
- Telefon GPS'iyle konum kaydı henüz yok.
