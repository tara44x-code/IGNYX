# IGNYX

**اشعل أي نظام من فلاشة وحدة.**

IGNYX تطبيق أندرويد يجهّز فلاشة USB قابلة للإقلاع لعدة أنظمة من الموبايل مباشرة، عن طريق وصلة OTG وبدون روت. تجهّز الفلاشة مرة وحدة، وبعدها تحط عليها ملفات ISO مثل أي ملف عادي، وعند تشغيل الحاسبة تطلع قائمة تختار منها النظام.

IGNYX مبني على فكرة وصيغة إقلاع [Ventoy](https://github.com/ventoy/Ventoy) (GPLv3)، وهو مشروع مستقل وما مرتبط رسمياً بـ Ventoy.

## الحالة الحالية (v0.1)

| الميزة | الحالة |
|---|---|
| ثيم النيون والأزرار المتحركة | ✅ جاهز |
| كشف الفلاشة عن طريق OTG وطلب الصلاحية | ✅ جاهز |
| عرض المساحة وقائمة ملفات ISO | ✅ جاهز (أقسام FAT32) |
| نسخ ملف ISO من الموبايل للفلاشة | ✅ جاهز (أقل من 4GB) |
| حساب تقسيم الفلاشة وبناء MBR متوافق ويا Ventoy | ✅ جاهز ومُختبر |
| شاشة التجهيز | 🟡 معاينة: تعرض الخطوات بدون كتابة |
| كتابة صور الإقلاع على الفلاشة (boot.img و core.img و ventoy.disk.img) | ⏳ المرحلة الجاية |
| فرمتة exFAT ودعم ملفات أكبر من 4GB | ⏳ المرحلة الجاية (NDK) |
| تنزيل الأنظمة من التطبيق، فحص checksum، تعديل ventoy.json | ⏳ لاحقاً |

## تنزيل APK

كل تحديث على فرع `main` يبنيه GitHub Actions تلقائياً:
**Actions ← آخر تشغيل لـ Build APK ← Artifacts ← IGNYX-debug-apk**.

## البناء محلياً

تحتاج JDK 17 و Android SDK (API 35):

```bash
./gradlew assembleDebug
```

الخطوط (Cairo و Chakra Petch، بترخيص SIL OFL) تنزل تلقائياً بأول بناء.

## هيكل المشروع

```
app/src/main/java/com/ignyx/app/
├── MainActivity.kt          تشغيل التطبيق واستقبال أحداث USB
├── AppViewModel.kt          حالة التطبيق
├── boot/VentoyLayout.kt     تقسيم الفلاشة وبناء MBR
├── usb/UsbDriveManager.kt   الوصول للفلاشة عبر libaums
└── ui/                      الثيم، الأزرار النيون، الشاشات
```

## الترخيص

GPL-3.0، نفس ترخيص Ventoy. شوف [LICENSE](LICENSE).

---

**English:** IGNYX is an Android app that prepares a Ventoy-compatible multi-boot USB drive from your phone over OTG, without root. Based on the boot format of Ventoy (GPLv3); not affiliated with the Ventoy project. Licensed under GPL-3.0.
