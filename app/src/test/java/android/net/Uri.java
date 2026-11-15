package android.net;

/**
 * Minimal JVM unit-test shadow for {@link Uri#parse(String)} so Kotlin non-null checks
 * on {@code Uri.parse(...)} succeed in pure JVM unit tests without Robolectric.
 */
public class Uri {
    private final String value;

    private Uri(String value) {
        this.value = value;
    }

    public static Uri parse(String uriString) {
        return new Uri(uriString);
    }

    @Override
    public String toString() {
        return value;
    }
}
