package studio.dbdiff;

/** Central application metadata, sourced from the Maven manifest. */
public final class AppInfo {
    public static final String NAME = nameFromManifest();

    private AppInfo() { }

    private static String nameFromManifest() {
        String manifestName = AppInfo.class.getPackage().getImplementationTitle();
        return manifestName == null || manifestName.isBlank() ? "DB-DiffForce" : manifestName;
    }
}
