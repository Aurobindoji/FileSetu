# FileSetu

**हिंदी** · [English](#english)

PC ↔ मोबाइल फ़ाइल ट्रांसफ़र, बिना internet। फ़ोन पर ऐप एक छोटा web server चलाता है। PC पर कुछ install नहीं
करना, सिर्फ़ browser (Chrome / Edge) चाहिए। फ़ोन के Hotspot, एक ही Wi-Fi, या USB tethering से चलता है।

**डाउनलोड:** [Releases](../../releases) → `FileSetu-x.y.z.apk` (Android 8.0 या नया)

## इस्तेमाल
1. PC को फ़ोन के Hotspot से जोड़ें (या दोनों एक Wi-Fi पर, या USB cable + USB tethering)।
2. ऐप में **चालू करें** दबाएँ।
3. PC के browser में ऐप पर दिखा पता (जैसे `http://192.168.43.1:8080`) खोलें और PIN डालें।
4. **PC → फ़ोन:** फ़ाइलें page पर खींचकर छोड़ें। फ़ोन में `Download/FileSetu` में सेव होती हैं।
5. **फ़ोन → PC:** ऐप में **+ फ़ाइल चुनें**, या किसी भी ऐप में Share → FileSetu; फिर PC page से डाउनलोड करें।

## खूबियाँ
- दोनों तरफ़ फ़ाइलें, कई एक साथ, progress और speed के साथ; सब फ़ाइलें एक ZIP में
- Text / link दोनों तरफ़ (नंबर, link, छोटा नोट)
- PIN से सुरक्षा; 15 मिनट कुछ न होने पर server अपने-आप बंद
- टूटा download फिर से शुरू (download managers के साथ भी)
- हिंदी / English, आई फ़ाइलों की history, notification
- कोई internet, cloud या account नहीं — फ़ाइलें आपके नेटवर्क से बाहर नहीं जातीं

## सुझाव
- Hotspot या USB पर फ़ोन का mobile data बंद रखें, ताकि PC उसका data न खाए; transfer फिर भी चलेगा।
- ऐप में "Battery setting खोलें" से battery optimization बंद करें, ताकि बड़ी फ़ाइल के समय फ़ोन transfer न रोके।
- कुछ दफ़्तरी Wi-Fi डिवाइसों को आपस में जुड़ने नहीं देते; तब Hotspot इस्तेमाल करें।

---

## English

PC ↔ Android file transfer without internet. The phone app runs a small web server; the PC installs nothing and
only needs a browser (Chrome / Edge). Works over the phone's hotspot, the same Wi-Fi, or USB tethering.

**Download:** [Releases](../../releases) → `FileSetu-x.y.z.apk` (Android 8.0 or newer)

### Usage
1. Connect the PC to the phone's hotspot (or put both on the same Wi-Fi, or use a USB cable + USB tethering).
2. Tap **Start** in the app.
3. Open the address shown in the app (e.g. `http://192.168.43.1:8080`) in the PC browser and enter the PIN.
4. **PC → phone:** drag files onto the page. They are saved on the phone in `Download/FileSetu`.
5. **Phone → PC:** tap **+ Choose files** in the app, or Share → FileSetu from any app; then download on the PC page.

### Features
- Files both ways, many at once, with progress and speed; everything as one ZIP
- Text / links both ways (numbers, links, short notes)
- PIN protection; the server stops itself after 15 idle minutes
- Resumable downloads (works with download managers)
- Hindi / English, history of received files, notifications
- No internet, cloud or account — files never leave your network

### Tips
- On hotspot or USB, turn off the phone's mobile data so the PC does not use it; transfers still work.
- Use "Open battery setting" in the app to turn off battery optimization, so big transfers are not paused.
- Some office Wi-Fi networks block device-to-device traffic; use the hotspot then.

### Building from source
Needs JDK 17+, the Android SDK (platform 36) and Gradle 8.14.

```
gradle assembleRelease
```

For a signed release build, put a `keystore.properties` file in the project root with `storeFile`, `storePassword`,
`keyAlias` and `keyPassword`; without it Gradle builds an unsigned APK. To try the PC page without a phone, run
`python tools/mock_server.py` and open http://localhost:8765 (PIN 1234).

## License
GPL-3.0 — see [LICENSE](LICENSE).
