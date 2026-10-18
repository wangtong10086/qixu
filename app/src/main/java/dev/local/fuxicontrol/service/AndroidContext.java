package dev.local.fuxicontrol.service;

import android.content.Context;
import android.os.Looper;

public final class AndroidContext {
    public static Context system() throws ReflectiveOperationException {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        return (Context) thread.getMethod("getSystemContext").invoke(instance);
    }
}
