# Portal GPhotos

A native Android app that allows you to pick photos and videos from your Google Photos library and display them as a slideshow on your Facebook Portal.

---

## 1. Prerequisites
- A Facebook Portal with Developer Mode (ADB) enabled.
- A computer with `adb` installed.
- [Download the latest release APK](https://github.com/ram-nat/portal-gphotos/releases/latest) and place it in the project directory, or clone this repo to build from source.

---

## 2. Google Cloud Platform Setup

Because this app connects to your personal Google Photos library, you need to create your own Google Cloud project and generate OAuth credentials.

1. **Create a project** at <https://console.cloud.google.com>.
2. **Enable the Photos Picker API**: APIs & Services → Library → search "Photos Picker
   API" → Enable. (Not the Library API, not the Ambient API.)
3. **OAuth consent screen**:
   - User type: **External**.
   - Add the scope `https://www.googleapis.com/auth/photospicker.mediaitems.readonly`.
   - Add your own Google account as a **Test user**.
4. **Create the OAuth client**: Credentials → Create credentials → OAuth client ID →
   Application type **Desktop app** → download the JSON (`client_secret.json`).

---

## 3. Deployment

Connect your Facebook Portal to your computer via USB (or over Wi-Fi ADB). Make sure to authorize the connection on the Portal screen.

Run the provided deployment script. This script automatically handles installing the APK, pushing your credentials, granting necessary system permissions, and setting up the screensaver hooks:

```bash
./scripts/deploy.sh --client client_secret.json
```

*(Note: If you have multiple devices connected, you can specify the target device with `-s <serial>`)*

---

## 4. Usage
- Long press on screen to bring up the menu.
- Swipe left/right or tap on left/right edges to navigate between photos.
- Settings screen to control slideshow settings, weather, etc.
- Swipe down from top to get the top bar when screensaver is active

---

## Screenshots

![Setup](docs/setup_screen.png)
![Picker](docs/add_photos_screen.png)
![Slideshow](docs/showing_screen.png)
