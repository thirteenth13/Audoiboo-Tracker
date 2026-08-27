# Audoiboo Tracker

Android-застосунок для відстеження книжкових серій на Audioboo.

## Реалізовано у v0.2

- додавання кількох серій Audioboo за URL;
- отримання списку книг через WebView DOM parser;
- локальне збереження бібліотеки на пристрої;
- статуси `NEW`, `UNREAD`, `READING`, `READ`;
- фільтри «Всі / Нові / Читаю / Прочитані»;
- збереження статусу при повторній синхронізації;
- відкриття сторінки книги в браузері;
- пошук посилання на ZIP/RAR/7Z або кнопку «Скачать/Download» на сторінці книги;
- перехоплення WebView download URL, якщо сайт генерує завантаження динамічно;
- завантаження архіву через Android DownloadManager із Cookie та Referer;
- GitHub Actions для автоматичної debug-збірки APK.

## Як користуватися

1. Відкрий застосунок і натисни `+ Серія`.
2. Вкажи назву та URL сторінки циклу Audioboo.
3. Відкрий серію та натисни `Оновити`.
4. Для книги змінюй статус кнопкою статусу.
5. Натисни `Знайти архів`, щоб застосунок відкрив сторінку книги у WebView та спробував знайти URL архіву.
6. Якщо URL знайдено, з'явиться `Завантажити архів`.

Audioboo може повертати HTTP 403 для простих HTTP-запитів, тому застосунок навмисно використовує Android WebView і cookies браузерної сесії.

## Технології

- Kotlin через вбудовану Kotlin-підтримку AGP 9
- Jetpack Compose
- Android WebView
- SharedPreferences + JSON для MVP-сховища
- Android DownloadManager
- compileSdk / targetSdk 37
- Android Gradle Plugin 9.3
- JDK 17
- GitHub Actions

## Збірка

GitHub Actions автоматично запускає debug-збірку після push у `main`.

```bash
gradle :app:assembleDebug
```

APK після успішного CI доступний в GitHub Actions як artifact `AudoibooTracker-debug`.

## Тестова серія

https://audioboo.org/xfsearch/cikl/%D0%94%D1%80%D1%83%D0%B3%D0%B0%D1%8F%20%D1%81%D1%82%D0%BE%D1%80%D0%BE%D0%BD%D0%B0/

## Далі

- уточнити DOM-селектори після тесту на реальному пристрої;
- фонове оновлення серій;
- Android-сповіщення про нові книги;
- імпорт/експорт бібліотеки;
- Room замість JSON після стабілізації моделі даних.
