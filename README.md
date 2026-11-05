# IGNYX

**اشعل أي نظام من فلاشة وحدة.**

IGNYX تطبيق أندرويد يجهّز فلاشة USB قابلة للإقلاع لعدة أنظمة من الموبايل مباشرة، عن طريق وصلة OTG وبدون روت. تجهّز الفلاشة مرة وحدة، وبعدها تحط عليها ملفات ISO مثل أي ملف عادي، وعند تشغيل الحاسبة تطلع قائمة تختار منها النظام.

IGNYX مبني على فكرة وصيغة إقلاع [Ventoy](https://github.com/ventoy/Ventoy) (GPLv3)، وهو مشروع مستقل وما مرتبط رسمياً بـ Ventoy.

## الحالة الحالية (v0.2)

| الميزة | الحالة |
|---|---|
| ثيم النيون والأزرار المتحركة | ✅ |
| الوصول للفلاشة عبر OTG بدون روت (درايفر USB/SCSI خاص بالتطبيق) | ✅ |
| تجهيز الفلاشة: محمّل الإقلاع، قسم EFI، فرمتة exFAT، جدول الأقسام | ✅ |
| نسخ وحذف ملفات ISO على exFAT (بدون حد 4GB) | ✅ |
| يتعرّف على فلاشات Ventoy المجهّزة من الحاسبة (وضع MBR) | ✅ |
| فلاشات أكبر من 2TB أو وضع GPT | ⏳ لاحقاً |
| تنزيل الأنظمة من التطبيق، فحص checksum، تعديل ventoy.json | ⏳ لاحقاً |

### شلون نتأكد إنها تقلع؟

ويه كل تحديث، GitHub Actions يسوي هذا تلقائياً:

1. ينزّل صور الإقلاع الرسمية من آخر إصدار لـ Ventoy.
2. يجهّز فلاشة وهمية (ملف image) بنفس كود التطبيق، ويضيف ملفات ويحذف ملفات.
3. يفحصها بأداة `fsck.exfat`، ويركّبها بلينكس ويتأكد من محتوى كل ملف.
4. يشغّلها بحاسبة وهمية (QEMU) بوضعين: BIOS و UEFI، ويقرأ قائمة Ventoy من الشاشة ويتأكد إن الملفات ظاهرة بيها.

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
├── usb/ScsiBlockDevice.kt    قراءة وكتابة الفلاشة مباشرة عبر USB
├── usb/UsbDriveManager.kt   كشف الفلاشة والتجهيز والنسخ
└── ui/                      الثيم، الأزرار النيون، الشاشات

core/src/main/kotlin/com/ignyx/core/
├── VentoyLayout.kt          تقسيم الفلاشة وبناء MBR والتعرّف عليها
├── VentoyInstaller.kt       خطوات التجهيز
├── ExFatFormatter.kt        فرمتة exFAT
└── ExFatVolume.kt           إضافة وحذف وعرض الملفات على exFAT
```

صور الإقلاع ما تنحفظ بالمستودع. قبل البناء محلياً شغّل:

```bash
scripts/fetch-ventoy.sh
```

## الترخيص

GPL-3.0، نفس ترخيص Ventoy. شوف [LICENSE](LICENSE).

---

**English:** IGNYX is an Android app that prepares a Ventoy-compatible multi-boot USB drive from your phone over OTG, without root. It writes the official Ventoy boot images, formats the data partition as exFAT with its own driver, and copies ISO files onto it. CI boots every build's disk image in QEMU (BIOS and UEFI). Based on the boot format of Ventoy (GPLv3); not affiliated with the Ventoy project. Licensed under GPL-3.0.
