# IPTV Player (Android)

مشغّل IPTV بسيط: بيشغّل قوائم M3U / Xtream Codes الخاصة بك (التطبيق مش بيوفّر أي قنوات).

- Kotlin + Media3 ExoPlayer (HLS / TS / MP4)
- بحث، تصنيفات (groups)، مفضلة (ضغطة مطوّلة)، قناة سابقة/تالية داخل المشغّل
- يدعم رابط M3U، بيانات Xtream، أو ملف M3U من الجهاز
- يعمل على الموبايل وAndroid TV / Fire TV (minSdk 21)

## بناء الـ APK
GitHub Actions بيبنيه تلقائيًا عند كل push (workflow: `Build IPTV APK`):
1. تبويب **Actions** ← آخر تشغيل ← Artifact `IPTV-Player-apk`، أو
2. تبويب **Releases** ← `iptv-latest` ← حمّل `IPTV-Player.apk`.

محليًا: `./gradlew assembleDebug` (محتاج JDK 17 + Android SDK) والناتج في `app/build/outputs/apk/debug/`.
