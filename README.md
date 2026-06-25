# Waze2Coordinate

אפליקציית Android native (Kotlin) להמרת קישורי Waze מקוצרים (`waze.com/ul/...`) לקואורדינטות (lat/lon).

## יכולות

1. **הדבקה ידנית** - מדביקים קישור Waze בשדה הטקסט ולוחצים "חלץ קואורדינטות".
2. **Share intent** - בכל אפליקציה (כולל Waze עצמו) ניתן לבחור "שתף" על מיקום, לבחור באפליקציה הזו, וההמרה מתבצעת אוטומטית.
3. **VIEW intent** - לחיצה ישירה על קישור `waze.com/ul/...` ובחירה באפליקציה הזו פותחת אותה ומבצעת המרה אוטומטית.

הפענוח מתבצע **native** דרך OkHttp שעוקב אחרי שרשרת ה-redirect של הקישור המקוצר - אין צורך בשום CORS proxy חיצוני (זה היה נחוץ רק בגרסת ה-HTML שרצה בדפדפן).

## בנייה מקומית (Termux)

```bash
cd ~/shimon/waze2coordinate
./gradlew assembleDebug
# ה-APK ייווצר ב: app/build/outputs/apk/debug/app-debug.apk
```

## CI/CD

כל push לענף `main` שמשנה קבצים תחת `app/`, `build.gradle`, `settings.gradle` או `gradle.properties` מפעיל build אוטומטי ב-GitHub Actions (לא רץ על שינויי תיעוד/README).
ה-build כולל Gradle cache בין הרצות, כך שרק קוד שהשתנה בפועל מתורגם מחדש.

ה-APK הבנוי זמין כ-artifact בהרצת ה-workflow (תפוגה: 30 יום).

## חתימה

הפרויקט משתמש ב-`debug.keystore` קבוע שמחויב ל-repo (עם `.gitattributes` לטיפול נכון בקובץ הבינארי), כדי שה-APK יהיה חתום בעקביות בכל build ולא ייצור התראת "אפליקציה לא חתומה" או קונפליקט התקנה בין גרסאות.
