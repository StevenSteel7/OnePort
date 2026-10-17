package com.samsung.android.feature;

import java.util.Collections;
import java.util.Map;

/**
 * Samsung's device capability table (/system/etc/floating_feature.xml on a Galaxy phone).
 * Every key is absent (false / 0 / ""), exactly like the generated stub this replaces. OnePort deliberately
 * reports no Galaxy AI capabilities: those features run on Samsung's servers for Galaxy devices only.
 */
public class SemFloatingFeature {
    private static final SemFloatingFeature INSTANCE = new SemFloatingFeature();
    private static final Map<String, String> VALUES = Collections.emptyMap();

    public static SemFloatingFeature getInstance() {
        return INSTANCE;
    }

    public boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    public boolean getBoolean(String key, boolean def) {
        String v = VALUES.get(key);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int def) {
        String v = VALUES.get(key);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String getString(String key) {
        return getString(key, "");
    }

    public String getString(String key, String def) {
        String v = VALUES.get(key);
        return v == null ? def : v;
    }
}
