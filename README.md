# FileSetu

PC ↔ मोबाइल फ़ाइल ट्रांसफ़र, बिना internet। फ़ोन पर ऐप एक छोटा web server चलाता है;
PC पर सिर्फ़ browser (Chrome/Edge) चाहिए। Hotspot, एक ही Wi-Fi, या USB tethering से चलता है।

## इस्तेमाल
1. PC को फ़ोन के Hotspot से जोड़ें (या दोनों एक Wi-Fi पर, या USB cable + USB tethering)।
2. ऐप में **चालू करें** दबाएँ।
3. PC के browser में ऐप पर दिखा पता (जैसे `http://192.168.43.1:8080`) खोलें, PIN डालें।
4. PC → फ़ोन: फ़ाइलें page पर खींचकर छोड़ें। फ़ोन में `Download/FileSetu` में सेव होती हैं।
5. फ़ोन → PC: ऐप में **+ फ़ाइल चुनें** या किसी भी ऐप में Share → FileSetu; PC page पर डाउनलोड करें।

## Build (इस PC पर)
```
set JAVA_HOME=D:\Android\jdk21
D:\Android\gradle-8.14.3\bin\gradle assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`.
Signing key `keystore/filesetu.jks` + `keystore.properties` git में नहीं हैं — **इनका backup रखें**;
key खो गई तो नया APK पुराने के ऊपर install नहीं होगा।

PC page को फ़ोन के बिना जाँचने के लिए: `python tools/mock_server.py` → http://localhost:8765 (PIN 1234)।

## Stage
- 0.1.0 (Stage 1): start/stop, पता + PIN, PC→फ़ोन upload, फ़ोन→PC download (picker + Share), progress।
- आगे (Stage 2): ZIP, text box, history, idle auto-stop, हिंदी/English।
