package studio.dbdiff;

/** Central application metadata, sourced from the Maven manifest. */
public final class AppInfo {
    public static final String NAME = nameFromManifest();
    public static final String VERSION = versionFromManifest();
    public static final String REPOSITORY = "https://github.com/ImNotThrasher/DB-Diff-Force";

    private AppInfo() { }

    private static String nameFromManifest() {
        String manifestName = AppInfo.class.getPackage().getImplementationTitle();
        return manifestName == null || manifestName.isBlank() ? "DB-Diff-Force" : manifestName;
    }

    private static String versionFromManifest() {
        String manifestVersion = AppInfo.class.getPackage().getImplementationVersion();
        return manifestVersion == null || manifestVersion.isBlank() ? "desarrollo" : manifestVersion;
    }
}
