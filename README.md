# Audoiboo Tracker

Android-застосунок для відстеження книжкових серій Audioboo, завантаження аудіокниг і локального прослуховування.

## Основні можливості

- додавання та фонове відстеження кількох серій Audioboo;
- швидкий парсинг через JSoup із WebView fallback для складних сторінок;
- статуси книг `NEW`, `UNREAD`, `READING`, `READ`;
- автоматичний пошук посилань на архіви;
- надійна черга завантажень із pause/resume/cancel, HTTP Range і перевіркою цілісності;
- розпакування архівів і структура `Автор → Серія → Книга`;
- SAF/MediaStore сумісне локальне сховище;
- вбудований Media3 audiobook player;
- черга відтворення, resume, позиція по треках і smart rewind;
- швидкість окремо для книги та серії;
- sleep timer, voice boost, broken-track tracking;
- історія прослуховування, закладки й статистика;
- теги та smart-фільтри бібліотеки;
- Android Auto / MediaLibraryService;
- віджет «Продовжити слухати»;
- резервне копіювання та WebDAV-синхронізація;
- автоматична перевірка серій через WorkManager.

## Зберігання даних

Основне сховище — Room.

Поточна схема Room v6 містить бібліотеку серій і книг, теги, чергу та resume плеєра, позиції треків, історію, закладки, статистику прослуховування, швидкості книг/серій, broken tracks і resume по серіях.

Налаштування застосунку зберігаються в Jetpack DataStore. SharedPreferences залишаються тільки там, де вони доречні для дрібних службових або окремих локальних параметрів.

## Backup

Поточний формат резервної копії: `format 11`.

Backup містить бібліотеку серій і книг, статуси та archive URL, Room-стан плеєра, теги, налаштування DataStore, дані завантажень і вибрані службові налаштування. Для вже існуючих серій збережено сумісний tracker-шлях імпорту/відновлення.

## Технології

- Kotlin
- Jetpack Compose + Material 3
- Room
- Jetpack DataStore
- Media3 / MediaLibraryService
- WorkManager
- JSoup + Android WebView fallback
- SAF / MediaStore
- compileSdk / targetSdk 37
- Android Gradle Plugin 9.x
- JDK 17
- GitHub Actions

## Збірка

CI запускається після push у `main`:

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleRelease
```

Workflow виконує unit tests, збирає підписаний release APK, завантажує artifact і публікує dev prerelease.

## Поточна dev-версія

`1.1.4-dev` (`versionCode 114`).
