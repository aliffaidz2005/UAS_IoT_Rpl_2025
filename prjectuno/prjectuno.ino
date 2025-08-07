#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include "DHT.h"
#include "BluetoothSerial.h"

BluetoothSerial SerialBT;

// WiFi & Telegram
const char* ssid = "STARLINK-STI";
const char* password = "STIstar*22";
const char* telegramBotToken = "8283215028:AAHKP_pX25PF0ssp9Eku7gFo9Sf7jODXWrs";
const char* chatId = "5602110194";

// Sensor config
const int pirPin = 13;
#define DHTPIN 26
#define DHTTYPE DHT11
DHT dht(DHTPIN, DHTTYPE);

// Flags & batas suhu
bool suhuAktif = true;
bool gerakAktif = true;
bool telegramAktif = true;

float suhuMin = 28.0;
float suhuMax = 35.0;

unsigned long lastMotionTime = 0;
unsigned long motionInterval = 2000;
unsigned long lastSuhuTime = 0;
unsigned long suhuInterval = 5000;

String lastBluetoothCommand = "";

void setup() {
  Serial.begin(115200);
  pinMode(pirPin, INPUT);
  dht.begin();

  SerialBT.begin("ESP32test");
  WiFi.begin(ssid, password);
  Serial.print("Menghubungkan WiFi");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  Serial.println("\nTerhubung ke WiFi");

  kirimPesan("ESP32 siap. Kontrol via Bluetooth aktif.");
}

void loop() {
  unsigned long now = millis();

  // Baca perintah dari Bluetooth
  if (SerialBT.available()) {
    String cmd = SerialBT.readStringUntil('\n');
    cmd.trim();
    cmd.toLowerCase();

    if (cmd != lastBluetoothCommand) {
      lastBluetoothCommand = cmd;
      handleBluetoothCommand(cmd);
    }
  }

  // Deteksi gerakan
  if (gerakAktif && now - lastMotionTime >= motionInterval) {
    int gerakan = digitalRead(pirPin);
    if (gerakan == HIGH) {
      kirimPesan("🚶 Gerakan terdeteksi!");
      lastMotionTime = now;
    }
  }

  // Deteksi suhu
  if (suhuAktif && now - lastSuhuTime >= suhuInterval) {
    lastSuhuTime = now;
    float suhu = dht.readTemperature();
    if (!isnan(suhu)) {
      if (suhu < suhuMin) {
        kirimPesan("🌡 Suhu RENDAH: " + String(suhu) + "°C (Min: " + String(suhuMin) + ")");
      } else if (suhu > suhuMax) {
        kirimPesan("🌡 Suhu TINGGI: " + String(suhu) + "°C (Max: " + String(suhuMax) + ")");
      } else {
        Serial.println("Suhu normal: " + String(suhu) + "°C");
      }
    } else {
      Serial.println("Gagal membaca suhu.");
    }
  }

  delay(200);
}

void kirimPesan(String pesan) {
  Serial.println("Pesan: " + pesan);

  if (SerialBT.hasClient()) {
    SerialBT.println(pesan);
    Serial.println("✅ Dikirim ke Bluetooth");
  } else if (telegramAktif && WiFi.status() == WL_CONNECTED) {
    HTTPClient http;
    String url = "https://api.telegram.org/bot" + String(telegramBotToken) + "/sendMessage";
    http.begin(url);
    http.addHeader("Content-Type", "application/x-www-form-urlencoded");
    String postData = "chat_id=" + String(chatId) + "&text=" + pesan;
    int httpResponseCode = http.POST(postData);
    http.end();

    if (httpResponseCode > 0) {
      Serial.println("✅ Dikirim ke Telegram");
    } else {
      Serial.println("❌ Gagal kirim Telegram. Status: " + String(httpResponseCode));
    }
  } else {
    Serial.println("⚠ Tidak ada koneksi aktif.");
  }
}

void handleBluetoothCommand(String cmd) {
  if (cmd == "on suhu") {
    suhuAktif = true;
    kirimPesan("✅ Sensor suhu AKTIF.");
  } else if (cmd == "off suhu") {
    suhuAktif = false;
    kirimPesan("❌ Sensor suhu NONAKTIF.");
  } else if (cmd == "on gerak") {
    gerakAktif = true;
    kirimPesan("✅ Sensor gerak AKTIF.");
  } else if (cmd == "off gerak") {
    gerakAktif = false;
    kirimPesan("❌ Sensor gerak NONAKTIF.");
  } else if (cmd.startsWith("set suhu min ")) {
    float minVal = cmd.substring(13).toFloat();
    if (minVal > 0) {
      suhuMin = minVal;
      kirimPesan("🔧 Batas suhu MIN diubah ke: " + String(suhuMin) + "°C");
    } else {
      kirimPesan("⚠ Format salah. Contoh: set suhu min 28");
    }
  } else if (cmd.startsWith("set suhu max ")) {
    float maxVal = cmd.substring(13).toFloat();
    if (maxVal > 0) {
      suhuMax = maxVal;
      kirimPesan("🔧 Batas suhu MAX diubah ke: " + String(suhuMax) + "°C");
    } else {
      kirimPesan("⚠ Format salah. Contoh: set suhu max 35");
    }
  } else if (cmd == "putus") {
    SerialBT.end();
    telegramAktif = true;
    delay(1000);
    kirimPesan("📲 Bluetooth diputus. Kembali kirim lewat Telegram.");
  } else {
    kirimPesan("❓ Perintah tidak dikenali.");
  }
}
