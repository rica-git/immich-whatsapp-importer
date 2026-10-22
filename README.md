# WhatsApp → Immich Sync (Android)

An Android application built with Jetpack Compose and Material 3 that seamlessly syncs your WhatsApp media (images and videos) to your self-hosted [Immich](https://immich.app/) server.

## 🚀 What the Application Does

Manually backing up WhatsApp media can be tedious and prone to duplicates. **WhatsApp → Immich** solves this by offering a direct, efficient bridge between your Android device's WhatsApp storage and your Immich server:

1. **Folder Selection & Scanning:** Browse and select specific WhatsApp media directories (such as `WhatsApp Images`, `WhatsApp Video`, or custom subfolders) with optional recursive subfolder scanning.
2. **SHA-1 Checksum & Duplicate Detection:** Computes SHA-1 hashes locally and checks them against your Immich server using the `/api/assets/bulk-upload-check` API to instantly identify new files versus files that already exist on the server.
3. **Tagging Support:** Assign a custom tag name (e.g., `whatsapp-2026`) during synchronization. The app automatically creates or retrieves the tag in Immich and applies it both to newly uploaded assets and existing matching assets.
4. **Smart Cleanup:** After synchronization, if duplicates are found on your device, the app gives you the option to review them and delete local WhatsApp copies to reclaim storage space.
5. **Comprehensive Reporting:** View a detailed summary of uploaded files, successfully deleted local files, files already existing on Immich, and any errors encountered.

---

## 📱 Features

- **Modern UI:** Built fully with Jetpack Compose and Material 3.
- **Robust Network handling:** Uses OkHttp with generous timeouts for reliable large media uploads.
- **Secure Configuration:** Stores your Immich Server URL and API Key securely using Android SharedPreferences.
- **Storage Management:** Full support for modern Android Scoped Storage and All Files Access permissions.

---

## 🛠️ Prerequisites & Immich API Permissions

To use this app, you need:
- An active **Immich** server instance.
- An **API Key** generated from your Immich account settings with the following permissions (or `tag.*` wildcard):
  - `asset.upload`
  - `asset.read`
  - `tag.create`
  - `tag.asset`

---

## 📖 How to Use

1. **Configure Immich:**
   - Open the app and tap the Settings icon (⚙️) in the top right.
   - Enter your Immich Server URL (e.g., `https://immich.yourdomain.com`).
   - Enter your API Key.
   - Save configuration.

2. **Grant Storage Permission:**
   - Tap **Choose Folder**.
   - If prompted, grant **All Files Access** (required on Android 11+) or storage permissions so the app can locate WhatsApp media directories.

3. **Select WhatsApp Media Folder:**
   - Use the built-in shortcuts or navigate through subfolders (e.g., `WhatsApp/Media/WhatsApp Images`).
   - Choose whether to include subfolders recursively or scan only the current directory.

4. **Synchronize:**
   - Tap **Sync folder with Immich**.
   - Enter a tag name for the batch (e.g., `whatsapp-chat-backup`).
   - Monitor progress as hashes are calculated, items are checked against Immich, files are uploaded, and tags are applied.
   - Review duplicate files found on your device and choose whether to keep or delete them locally to free up space.
   - View the final synchronization report.

---

## 🏗️ Building from Source

To build the APK yourself:

1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/whatsapp-immich.git
   cd whatsapp-immich
   ```
2. Build the debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   The APK will be located at:
   `app/build/outputs/apk/debug/app-debug.apk`

3. Build the release APK:
   ```bash
   ./gradlew assembleRelease
   ```
   The APK will be located at:
   `app/build/outputs/apk/release/app-release.unsigned.apk` (or signed if you configure signing configs in `app/build.gradle.kts`).

---

## 📦 Releasing and Publishing APKs on GitHub

To publish the APK in GitHub Releases:
1. Build the APK:
   ```bash
   ./gradlew assembleRelease
   ```
2. Go to your GitHub repository -> **Releases** -> **Draft a new release**.
3. Tag the release (e.g., `v2.0.0`).
4. Upload the compiled APK (`app-release.apk` or `app-debug.apk`) as a release asset.
5. Publish the release so users can download and install it directly on their Android devices.

---

## 📄 License

This project is open-source and available under the [MIT License](LICENSE).
