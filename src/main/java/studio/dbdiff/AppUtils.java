package studio.dbdiff;

/** Small helpers shared by independent UI components. */
final class AppUtils {
    private AppUtils() { }

    static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }
}
