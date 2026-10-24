package org.bitcoinj.utils;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/**
 * Loads bitcoinj bundled resources from the normal classpath, falling back to Android assets.
 * This is used because the bitcoinj sources are compiled directly in the Android app module.
 */
public final class AndroidResourceLoader {
    private static volatile Context applicationContext;

    private AndroidResourceLoader() { }

    /** Call once from the application's onCreate before wallet/node code uses bitcoinj resources. */
    public static void initialize(Context context) {
        if (context != null) {
            Context app = context.getApplicationContext();
            applicationContext = app != null ? app : context;
        }
    }

    /** Mirrors Class.getResourceAsStream semantics, with an Android assets fallback. */
    public static InputStream getResourceAsStream(Class<?> owner, String resourceName) {
        if (owner == null || resourceName == null || resourceName.isEmpty()) {
            return null;
        }

        InputStream classpathStream = owner.getResourceAsStream(resourceName);
        if (classpathStream != null) {
            return classpathStream;
        }

        Context context = applicationContext;
        if (context == null) {
            return null;
        }

        String assetPath;
        if (resourceName.startsWith("/")) {
            assetPath = resourceName.substring(1);
        } else {
            Package ownerPackage = owner.getPackage();
            String packagePath = ownerPackage == null
                    ? ""
                    : ownerPackage.getName().replace('.', '/');
            assetPath = packagePath.isEmpty()
                    ? resourceName
                    : packagePath + "/" + resourceName;
        }

        try {
            return context.getAssets().open(assetPath);
        } catch (IOException ignored) {
            return null;
        }
    }
}
