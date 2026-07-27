#!/bin/bash
# ============================================================
#  ELLE TEST BETIGI
#
#  Ne ise yarar: demo-api uygulamasina sirayla istek gonderir,
#  her adimda ONCE calistirdigi komutu, SONRA gelen cevabi basar.
#  Boylece "ne yazinca ne geliyor" acikca gorunur.
#
#  Nasil calistirilir:
#    1) Bir terminalde uygulamayi baslat:  ./gradlew :demo-api:bootRun
#    2) Baska bir terminalde:              ./deneme.sh
# ============================================================

API="http://localhost:8080/api"
DB="deneme_okul"          # test icin kullanilacak veritabani
COL="ogrenciler"          # test icin kullanilacak koleksiyon

# Her adimi ekrana basan yardimci: once aciklama, sonra komut, sonra cevap.
adim() {
    local aciklama="$1"
    local komut="$2"
    echo ""
    echo "------------------------------------------------------------"
    echo ">> $aciklama"
    echo ""
    echo "   KOMUT : $komut"
    echo -n "   CEVAP : "
    eval "$komut"
    echo ""
}

echo "============================================================"
echo " DBMS Arka Yuz - elle test"
echo " Uygulama adresi: $API"
echo "============================================================"

# Uygulama ayakta mi? Degilse anlamli bir uyari verip cikalim.
if ! curl -s -o /dev/null --max-time 5 "$API/health"; then
    echo ""
    echo "HATA: Uygulamaya ulasilamadi."
    echo "Once BASKA bir terminalde sunu calistirin:"
    echo "    ./gradlew :demo-api:bootRun"
    echo "Uygulama acildiktan sonra bu betigi tekrar calistirin."
    exit 1
fi

adim "1) Veritabani sunucusu ayakta mi? (Ister_0018)" \
     "curl -s $API/health"

adim "2) Sistemdeki veritabanlarini listele" \
     "curl -s $API/databases"

adim "3) Yeni kayit ekle - DUZ veri (Ister_0017)" \
     "curl -s -X POST $API/$DB/$COL -H 'Content-Type: application/json' -d '{\"ad\":\"Ali\",\"sinif\":3}'"

adim "4) Yeni kayit ekle - IC ICE veri (nesne + dizi)" \
     "curl -s -X POST $API/$DB/$COL -H 'Content-Type: application/json' -d '{\"ad\":\"Zeynep\",\"bolum\":{\"ad\":\"Bilgisayar\",\"yil\":3},\"dersler\":[{\"kod\":\"BIL101\"},{\"kod\":\"MAT201\"}]}'"

adim "5) Eklenenleri geri oku (Ister_0016)" \
     "curl -s $API/$DB/$COL"

adim "6) Filtreli okuma - sadece 3. siniftakiler" \
     "curl -s '$API/$DB/$COL?sinif=3'"

adim "7) Guncelle - Ali'yi 4. sinifa gecir" \
     "curl -s -X PUT $API/$DB/$COL -H 'Content-Type: application/json' -d '{\"filter\":{\"ad\":\"Ali\"},\"newValues\":{\"sinif\":4}}'"

adim "8) Guncellemeyi dogrula - Ali'nin sinifi degisti mi?" \
     "curl -s '$API/$DB/$COL?ad=Ali'"

adim "9) Sil - Zeynep'i kaldir" \
     "curl -s -X DELETE '$API/$DB/$COL?ad=Zeynep'"

adim "10) Son durum - Zeynep gitti mi?" \
     "curl -s $API/$DB/$COL"

echo ""
echo "============================================================"
echo " Test bitti."
echo ""
echo " NASIL YORUMLANIR:"
echo "   - 3. ve 4. adimda ekledigin kayitlar 5. adimda goruntulendi mi?"
echo "   - 7. adimdaki degisiklik 8. adimda yansidi mi?"
echo "   - 9. adimda sildigin kayit 10. adimda kayboldu mu?"
echo "   Uc soruya da EVET ise sistem dogru calisiyor."
echo ""
echo " status/hata gorursen: UNAUTHORIZED = yetki/sifre sorunu,"
echo " Bad Gateway (502) = ara katmana ulasilamiyor."
echo "============================================================"
