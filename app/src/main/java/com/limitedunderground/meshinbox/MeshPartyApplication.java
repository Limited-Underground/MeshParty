package com.limitedunderground.meshinbox;

import android.app.Application;

public final class MeshPartyApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        MeshDiagnostics.initialize(this);
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
