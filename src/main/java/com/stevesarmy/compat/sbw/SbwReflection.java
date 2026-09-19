package com.stevesarmy.compat.sbw;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Version-tolerant reflective access to Superb Warfare without compile-time imports.
 */
public final class SbwReflection {

    private static final Object MISS = new Object();

    private static final Map<String, Object> CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Object> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Object> FIELDS = new ConcurrentHashMap<>();

    private SbwReflection() {
    }

    public static Class<?> cls(String name) {
        Object cached = CLASSES.computeIfAbsent(name, n -> {
            try {
                return Class.forName(n);
            } catch (Throwable t) {
                return MISS;
            }
        });
        return cached == MISS ? null : (Class<?>) cached;
    }

    public static Method method(Class<?> owner, int argCount, String... names) {
        if (owner == null) return null;
        String key = owner.getName() + "#" + argCount + "#" + String.join(",", names);
        Object cached = METHODS.computeIfAbsent(key, k -> {
            for (String name : names) {
                for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                            try {
                                m.setAccessible(true);
                                return m;
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }
            return MISS;
        });
        return cached == MISS ? null : (Method) cached;
    }

    public static Field field(Class<?> owner, String... names) {
        if (owner == null) return null;
        String key = owner.getName() + "$" + String.join(",", names);
        Object cached = FIELDS.computeIfAbsent(key, k -> {
            for (String name : names) {
                for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        return f;
                    } catch (Throwable ignored) {
                    }
                }
            }
            return MISS;
        });
        return cached == MISS ? null : (Field) cached;
    }

    public static Object call(Object target, String[] names, Object... args) {
        if (target == null) return null;
        Method m = method(target.getClass(), args.length, names);
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object callStatic(Class<?> owner, String[] names, Object... args) {
        Method m = method(owner, args.length, names);
        if (m == null) return null;
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Object get(Object target, String... names) {
        if (target == null) return null;
        Object viaMethod = call(target, names);
        if (viaMethod != null) return viaMethod;
        Method m = method(target.getClass(), 0, names);
        if (m != null) return null;
        Field f = field(target.getClass(), names);
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean set(Object target, Object value, String... names) {
        if (target == null) return false;
        String[] setters = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String n = names[i];
            setters[i] = "set" + Character.toUpperCase(n.charAt(0)) + n.substring(1);
        }
        Method m = method(target.getClass(), 1, setters);
        if (m != null) {
            try {
                m.invoke(target, value);
                return true;
            } catch (Throwable ignored) {
            }
        }
        Field f = field(target.getClass(), names);
        if (f == null) return false;
        try {
            f.set(target, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean asBool(Object o, boolean fallback) {
        return o instanceof Boolean b ? b : fallback;
    }

    public static int asInt(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    public static float asFloat(Object o, float fallback) {
        return o instanceof Number n ? n.floatValue() : fallback;
    }

    public static double asDouble(Object o, double fallback) {
        return o instanceof Number n ? n.doubleValue() : fallback;
    }

    public static String asString(Object o, String fallback) {
        return o instanceof String s ? s : fallback;
    }
}