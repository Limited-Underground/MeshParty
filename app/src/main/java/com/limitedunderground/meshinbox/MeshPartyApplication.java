package com.limitedunderground.meshinbox;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.res.Resources;

public final class MeshPartyApplication extends Application {
    // Text.t looks strings up by name for classes without a Context (D-197).
    @SuppressLint("DiscouragedApi")
    @Override public void onCreate() {
        super.onCreate();
        MeshDiagnostics.initialize(this);
        // Text for classes without a Context comes from the string resources in the app language.
        Text.use(key -> {
            Resources resources = AppLanguage.resources(this);
            int id = resources.getIdentifier(key, "string", getPackageName());
            if (id == 0) {
                try {
                    id = R.string.class.getField(key).getInt(null);
                } catch (ReflectiveOperationException missing) {
                    return null;
                }
            }
            return resources.getString(id);
        });
        Thread.UncaughtExceptionHandler systemHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (systemHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
                try {
                    // Best effort only. Never inspect the exception or delay Android's crash handling.
                    MeshDiagnostics.recordUncaughtCrash();
                } finally {
                    systemHandler.uncaughtException(thread, failure);
                }
            });
        }
    }
}
