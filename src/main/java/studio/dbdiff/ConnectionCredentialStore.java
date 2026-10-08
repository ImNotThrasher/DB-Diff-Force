package studio.dbdiff;

import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;

/** Keeps optional saved passwords in the current operating system's credential store. */
final class ConnectionCredentialStore {
    private static final String SERVICE = AppInfo.NAME + " database connections";

    private ConnectionCredentialStore() { }

    static void save(String profileName, char[] password) throws Exception {
        try (Keyring keyring = Keyring.create()) {
            keyring.setPassword(SERVICE, account(profileName), new String(password));
        }
    }

    static char[] load(String profileName) throws Exception {
        try (Keyring keyring = Keyring.create()) {
            String password = keyring.getPassword(SERVICE, account(profileName));
            return password == null ? new char[0] : password.toCharArray();
        }
    }

    static void delete(String profileName) throws Exception {
        try (Keyring keyring = Keyring.create()) {
            keyring.deletePassword(SERVICE, account(profileName));
        } catch (PasswordAccessException ex) {
            // Removing a profile should remain possible when no credential was saved.
            if (!isNotFound(ex)) throw ex;
        }
    }

    private static boolean isNotFound(PasswordAccessException exception) {
        String message = exception.getMessage();
        return message != null && (message.toLowerCase(java.util.Locale.ROOT).contains("not found")
                || message.toLowerCase(java.util.Locale.ROOT).contains("no matching"));
    }

    private static String account(String profileName) { return profileName.trim(); }
}
