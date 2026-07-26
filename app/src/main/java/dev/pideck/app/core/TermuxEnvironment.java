package dev.pideck.app.core;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Verified Android-side compatibility state for Termux and its optional API companion. */
public final class TermuxEnvironment {
    public enum Source {
        F_DROID,
        GITHUB_SHARED_TEST_KEY,
        UNKNOWN,
        ABSENT
    }

    public final boolean installed;
    public final String version;
    public final boolean versionSupported;
    public final Source source;
    public final String signerSha256;
    public final boolean apiInstalled;
    public final String apiVersion;
    public final boolean apiCompatible;

    private TermuxEnvironment(
            boolean installed,
            String version,
            boolean versionSupported,
            Source source,
            String signerSha256,
            boolean apiInstalled,
            String apiVersion,
            boolean apiCompatible
    ) {
        this.installed = installed;
        this.version = version;
        this.versionSupported = versionSupported;
        this.source = source;
        this.signerSha256 = signerSha256;
        this.apiInstalled = apiInstalled;
        this.apiVersion = apiVersion;
        this.apiCompatible = apiCompatible;
    }

    public boolean canRunCommands() {
        return installed && versionSupported && source != Source.UNKNOWN;
    }

    public boolean signerTrusted() {
        return source == Source.F_DROID;
    }

    public String sourceLabel() {
        return switch (source) {
            case F_DROID -> "F-DROID TRUSTED";
            case GITHUB_SHARED_TEST_KEY -> "GITHUB SHARED TEST KEY";
            case UNKNOWN -> "UNKNOWN SIGNER";
            case ABSENT -> "ABSENT";
        };
    }

    static TermuxEnvironment inspect(Context context) {
        Compatibility compatibility = Compatibility.load(context);
        PackageManager packages = context.getPackageManager();
        PackageInfo termux = packageInfo(packages, TermuxBridge.PACKAGE);
        if (termux == null) {
            return new TermuxEnvironment(
                    false, "", false, Source.ABSENT, "",
                    false, "", false
            );
        }
        String version = termux.versionName == null ? "" : termux.versionName;
        String signer = signerSha256(termux);
        Source source = classifySigner(
                signer,
                compatibility.fdroidSigner,
                compatibility.githubSharedSigner
        );
        PackageInfo api = packageInfo(packages, compatibility.apiPackage);
        boolean apiInstalled = api != null;
        String apiVersion = apiInstalled && api.versionName != null ? api.versionName : "";
        boolean sameSigner = apiInstalled && signer.equals(signerSha256(api));
        boolean apiCompatible = apiInstalled
                && sameSigner
                && versionAtLeast(apiVersion, compatibility.minimumApiVersion);
        return new TermuxEnvironment(
                true,
                version,
                versionAtLeast(version, compatibility.minimumTermuxVersion),
                source,
                signer,
                apiInstalled,
                apiVersion,
                apiCompatible
        );
    }

    static Source classifySigner(String actual, String fdroid, String githubShared) {
        if (actual != null && actual.equalsIgnoreCase(fdroid)) return Source.F_DROID;
        if (actual != null && actual.equalsIgnoreCase(githubShared)) {
            return Source.GITHUB_SHARED_TEST_KEY;
        }
        return Source.UNKNOWN;
    }

    static boolean versionAtLeast(String actual, String minimum) {
        int[] left = numericVersion(actual);
        int[] right = numericVersion(minimum);
        if (left == null || right == null) return false;
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = i < left.length ? left[i] : 0;
            int b = i < right.length ? right[i] : 0;
            if (a != b) return a > b;
        }
        return true;
    }

    private static int[] numericVersion(String value) {
        if (value == null) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?")
                .matcher(value.trim());
        if (!matcher.find()) return null;
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
        };
    }

    private static PackageInfo packageInfo(PackageManager manager, String packageName) {
        try {
            int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            return manager.getPackageInfo(packageName, flags);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static String signerSha256(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length != 1) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(signatures[0].toByteArray());
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Android has no SHA-256 provider", impossible);
        }
    }

    private static final class Compatibility {
        final String minimumTermuxVersion;
        final String fdroidSigner;
        final String githubSharedSigner;
        final String apiPackage;
        final String minimumApiVersion;

        Compatibility(
                String minimumTermuxVersion,
                String fdroidSigner,
                String githubSharedSigner,
                String apiPackage,
                String minimumApiVersion
        ) {
            this.minimumTermuxVersion = minimumTermuxVersion;
            this.fdroidSigner = fdroidSigner;
            this.githubSharedSigner = githubSharedSigner;
            this.apiPackage = apiPackage;
            this.minimumApiVersion = minimumApiVersion;
        }

        static Compatibility load(Context context) {
            try (InputStream input = context.getAssets().open("compatibility.json")) {
                JSONObject root = new JSONObject(readBounded(input));
                JSONObject termux = root.getJSONObject("termux");
                JSONObject trusted = termux.getJSONObject("trustedSigners");
                JSONObject insecure = termux.getJSONObject("knownInsecureSigners");
                JSONObject api = root.getJSONObject("termuxApi");
                return new Compatibility(
                        termux.getString("minimumVersion"),
                        trusted.getString("fdroid"),
                        insecure.getString("githubSharedTestKey"),
                        api.getString("package"),
                        api.getString("minimumVersion")
                );
            } catch (IOException | JSONException error) {
                throw new IllegalStateException("Bundled Termux compatibility data is invalid", error);
            }
        }

        private static String readBounded(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
                if (output.size() > 256 * 1024) {
                    throw new IOException("Compatibility asset is oversized");
                }
            }
            return new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
