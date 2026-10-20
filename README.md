# FAIROSVPN

این مخزن کد دو برنامه‌ی اندروید را دارد:

- **app** — برنامه‌ی عمومی برای کاربران (سرورهای رایگان و پرو، تبلیغ قبل از اتصال، تست سرعت، اطلاعیه، آپدیت اجباری)
- **admin** — برنامه‌ی مدیریت، فقط برای گوشی صاحب برنامه (ویرایش سرورها، تبلیغات، اطلاعیه، لینک خرید و…)

## فایل‌های مهم

| فایل | کاربرد |
| --- | --- |
| `app.properties` | اسم برنامه، شماره‌ی نسخه |
| `remote/config.json` | تنظیمات زنده‌ی برنامه (از طریق برنامه‌ی مدیریت عوض می‌شود؛ دستی ویرایش نکنید) |
| `.github/workflows/build.yml` | ساخت خودکار APK با هر تغییر کد |

## دانلود

آخرین نسخه‌ی برنامه‌ی عمومی در بخش **Releases** است:

- `app-arm64-v8a.apk` — بیشتر گوشی‌ها
- `app-armeabi-v7a.apk` — گوشی‌های قدیمی
- `app-universal.apk` — همه‌ی گوشی‌ها (حجم بیشتر)

برنامه‌ی مدیریت در یک Release خصوصی (Draft) قرار می‌گیرد که فقط صاحب مخزن آن را می‌بیند.

هسته‌ی اتصال: [Xray-core](https://github.com/XTLS/Xray-core) از طریق [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).
فونت: [Vazirmatn](https://github.com/rastikerdar/vazirmatn) (OFL).
