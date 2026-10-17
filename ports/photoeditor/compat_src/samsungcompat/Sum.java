package samsungcompat;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Samsung Unified Media (com.samsung.vcore.sum) without libsum_jni.media.samsung.so.
 * Only SkiaPlugin (BitmapFactory / Bitmap.compress) registers on a non-Samsung phone, so before UniImgpBuilder.build()
 * rewrite its stage list: DECODE/ENCODE on a missing plugin -> SKIA; any other stage whose plugin is missing
 * (RESIZE/CVT_COLOR via OpenCV/SIMGP, gainmap, ...) is dropped. Called from patched smali with the builder.
 */
public final class Sum {
    private static final String TAG = "SumCompat";
    private static final String PKG = "com.samsung.vcore.sum.";

    private Sum() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void filter(Object builder) {
        try {
            ClassLoader cl = builder.getClass().getClassLoader();
            Class<?> registry = cl.loadClass(PKG + "core.plugin.GlobalPluginRegistry");
            Class<?> pluginType = cl.loadClass(PKG + "core.plugin.ImgpPlugin$Type");
            Class<?> imgpType = cl.loadClass(PKG + "core.types.ImgpType");
            Method getBinder = registry.getMethod("getPluginBinder", pluginType);
            Method supported = registry.getMethod("findSupportedPluginTypes", imgpType);
            Object skia = Enum.valueOf((Class) pluginType, "SKIA");

            Field listF = builder.getClass().getDeclaredField("imgpList");
            listF.setAccessible(true);
            List<Object> list = (List<Object>) listF.get(builder);
            StringBuilder before = new StringBuilder(), after = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                Object pair = list.get(i);
                Field firstF = pair.getClass().getField("first"), secondF = pair.getClass().getField("second");
                Object stage = firstF.get(pair), plugin = secondF.get(pair);
                before.append(stage).append('/').append(plugin).append(' ');
                String stageName = ((Enum<?>) stage).name();
                if ("ANY".equals(stageName)) continue;
                boolean available = "ANY".equals(((Enum<?>) plugin).name())
                        ? !((List<?>) supported.invoke(null, stage)).isEmpty()
                        : getBinder.invoke(null, plugin) != null;
                if (available) continue;
                if ("DECODE".equals(stageName) || "ENCODE".equals(stageName)) {
                    Constructor<?> c = pair.getClass().getConstructor(Object.class, Object.class);
                    list.set(i, c.newInstance(stage, skia));
                } else {
                    list.remove(i--);
                }
            }
            for (Object pair : list) {
                after.append(pair.getClass().getField("first").get(pair)).append('/')
                        .append(pair.getClass().getField("second").get(pair)).append(' ');
            }
            if (!before.toString().equals(after.toString())) {
                Log.i(TAG, "UniImgp stages [" + before.toString().trim() + "] -> [" + after.toString().trim() + "]");
            }
        } catch (Throwable t) {
            Log.w(TAG, "filter failed", t);
        }
    }
}
