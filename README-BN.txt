BGB_Upscaler Android project — Neural AI integration
=====================================================

Android Studio-তে এই ফোল্ডারটি Open করুন।

প্রয়োজন:
- Android Studio
- JDK 17
- Android SDK Platform 35
- Gradle plugin 8.7.3 (Gradle wrapper Android Studio চাইলে তৈরি/ডাউনলোড করবে)

APK:
1. Android Studio > Open > android ফোল্ডার।
2. Gradle Sync সম্পন্ন করুন।
3. Build > Build Bundle(s) / APK(s) > Build APK(s)।
4. Debug APK: app/build/outputs/apk/debug/app-debug.apk

Offline AI:
- Qualcomm-এর Real-ESRGAN-x4plus TFLite model প্রথমবার Download Offline AI Model বোতাম চাপলে ডাউনলোড হবে।
- Model প্রায় 67 MB; প্রথম ডাউনলোডে ইন্টারনেট লাগবে।
- TensorFlow Lite runtime মডেল খুলে যাচাই করলে মডেল ফোনে থাকবে; পরবর্তী সময়ে Offline AI চলবে।
- 4× neural output model থেকে আসে; 2× নির্বাচন করলে 4× output high-quality downscale করা হয়।
- Model runtime dependency: TensorFlow Lite 2.17.0।
- বড় ছবি মেমোরি সীমার কারণে প্রত্যাখ্যাত হতে পারে।

Browser/HTML:
- HTML সংস্করণে Neural AI model runner নেই; সেখানে offline smart resize + sharpening fallback আছে।
- Android APK-তে native TFLite bridge-এর মাধ্যমে Neural AI যুক্ত।

Online AI:
- Online AI mode-এ নিজস্ব HTTPS endpoint ও API key প্রয়োজন। Endpoint POST multipart/form-data-এ image ও scale নেবে এবং image response ফেরত দেবে।
- কোনো তৃতীয় পক্ষের API key বা সার্ভার এই প্রজেক্টে অন্তর্ভুক্ত নয়।

আইকন:
- ব্যবহারকারীর দেওয়া portrait image থেকে launcher icon তৈরি করা হয়েছে।

মডেল attribution ও license সংক্রান্ত নোট MODEL-ATTRIBUTION.txt ফাইলে।
