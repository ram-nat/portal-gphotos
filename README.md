# Portal GPhotos

A native Android app specifically designed to run on the discontinued Meta Portal (which runs a GMS-less Android 9/10 fork). It connects directly to your Google Photos library and operates as an immersive, highly customizable digital photo frame.

## Features
- Direct integration with Google Photos API
- Seamless video playback
- Weather overlay based on location
- Smart caching to minimize bandwidth and handle offline scenarios
- No dependence on Google Play Services (GMS)

---

## 1. Prerequisites
- A Meta Portal with Developer Mode (ADB) enabled.
- A computer with `adb` installed.
- [Download the latest release APK](https://github.com/ram-nat/portal-gphotos/releases/latest) and place it in the project directory, or clone this repo to build from source.

---

## 2. Google Cloud Platform Setup

Because this app connects to your personal Google Photos library, you need to create your own Google Cloud project and generate OAuth credentials.

### Step A: Create the Project & Enable API
1. Go to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project (e.g., "Portal Frame").
3. Navigate to **APIs & Services > Library**.
4. Search for the **Photos Library API** and click **Enable**.

### Step B: Configure OAuth Consent Screen
1. Go to **APIs & Services > OAuth consent screen**.
2. Select **External** user type and click **Create**.
3. Fill out the required App information (App name, support email, developer contact).
4. Add your own Google account email under **Test Users** (important, otherwise you can't log in).
5. Save and continue. You do not need to publish the app.

### Step C: Create Credentials
1. Go to **APIs & Services > Credentials**.
2. Click **Create Credentials** -> **OAuth client ID**.
3. For Application type, select **Desktop app**. Name it anything (e.g., "Portal Client").
4. Click **Create**.
5. A dialog will appear with your Client ID and Secret. Click **Download JSON** and save this file to the root directory of this project as exactly: `client_secret.json`

---

## 3. Deployment

Connect your Meta Portal to your computer via USB (or over Wi-Fi ADB). Make sure to authorize the connection on the Portal screen.

Run the provided deployment script. This script automatically handles installing the APK, pushing your credentials, granting necessary system permissions, and setting up the screensaver hooks:

```bash
./scripts/deploy.sh --client client_secret.json
```

*(Note: If you have multiple devices connected, you can specify the target device with `-s <serial>`)*

---

## 4. First Run

When the app launches on your Portal for the first time, it will show a "Setup Needed" screen.
1. Tap the **Sign in on this device** button.
2. It will display a QR code and a link.
3. Scan the QR code with your phone (or go to the link on your computer).
4. Log in with the exact Google account you added as a "Test User" in Step B.
5. Grant the permissions.

Once authenticated, the Portal will automatically refresh, download your library structure, and begin the slideshow!
