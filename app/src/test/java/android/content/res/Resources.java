package android.content.res;

import android.util.DisplayMetrics;

/**
 * Minimal JVM unit-test shadow for {@link Resources#getSystem()} so {@code Downloader}
 * can read {@code Resources.getSystem().getDisplayMetrics()} without a device or Robolectric.
 */
public class Resources {
    private static final Resources SYSTEM = new Resources();
    private final DisplayMetrics metrics = new DisplayMetrics();

    public Resources() {
        metrics.widthPixels = 1280;
        metrics.heightPixels = 800;
    }

    public static Resources getSystem() {
        return SYSTEM;
    }

    public DisplayMetrics getDisplayMetrics() {
        return metrics;
    }
}
