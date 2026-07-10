package com.vince.geminimi.hooks;

import android.content.Context;

import com.vince.geminimi.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 接管长按电源键，发送 ACTION_ASSIST，由系统路由到当前数字助理 (Gemini) 的 Overlay。
 * 国行 HyperOS 通常在 PhoneWindowManager 的 launchAssistAction / launchVoiceAssist
 * 或 MIUI 自定义的 launchSuperXiaoAi 上做分支。我们覆盖最常见的几个签名。
 */
public final class PowerKeyOverlayHook {

    private PowerKeyOverlayHook() {}

    private static final String PWM = "com.android.server.policy.PhoneWindowManager";
    private static final int SHOW_WITH_ASSIST = 1;
    private static final int SHOW_WITH_SCREENSHOT = 1 << 1;
    private static final int SHOW_SOURCE_PUSH_TO_TALK = 1 << 4;
    private static final int SHOW_POWER_ASSIST_WITH_SCREENSHOT =
            SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT | SHOW_SOURCE_PUSH_TO_TALK;

    public static void apply(XC_LoadPackage.LoadPackageParam lpp) {
        ClassLoader cl = lpp.classLoader;

        Class<?> pwm;
        try {
            pwm = XposedHelpers.findClass(PWM, cl);
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " PhoneWindowManager not found: " + t);
            return;
        }

        // 不再枚举固定名字。HyperOS 各版本把方法叫 launchSuperXiaoAi /
        // launchXiaoAiOnPowerLong / launchXiaoAiByLongPressPower / launchAiKey ...
        // 都不一样。统一用名字模式扫描：含 XiaoAi / Assist / Voice / AiKey 的全接管。
        int hooked = 0;
        for (java.lang.reflect.Method m : pwm.getDeclaredMethods()) {
            if (!HookPolicy.shouldHookPowerMethod(m)) continue;
            hookOne(pwm, m);
            hooked++;
        }
        XposedBridge.log(Constants.TAG + " PowerKeyOverlay hooked " + hooked + " method(s)");
    }

    private static void hookOne(Class<?> clazz, java.lang.reflect.Method m) {
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject == null) return;
                    Context ctx = (Context) XposedHelpers
                            .getObjectField(param.thisObject, "mContext");
                    if (ctx != null && sendAssist(ctx)) {
                        param.setResult(interceptResult(m));
                    }
                } catch (Throwable t) {
                    XposedBridge.log(Constants.TAG + " " + m.getName()
                            + " callback failed; falling back to original method: " + t);
                }
            }
        });
        XposedBridge.log(Constants.TAG + " hooked " + clazz.getSimpleName()
                + "#" + m.getName() + " " + m);
    }

    private static Object interceptResult(java.lang.reflect.Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == Void.TYPE) return null;
        if (rt == Boolean.TYPE) return true;
        if (rt == Integer.TYPE) return 0;
        throw new IllegalArgumentException("Unsupported return type: " + rt);
    }

    private static boolean sendAssist(Context ctx) {
        // Bard 自身没声明 VoiceInteractionService，唯一弹 overlay 的路径是
        // Pixel 那条："framework → GSB VIS → GSB 把请求 forward 给 Bard overlay"。
        // 前提：voice_interaction_service 必须是 GSB 那个组件。MIUI 在开机后会
        // 把它回写成小爱 VIS，所以这里每次触发都现场改一次（system_server 自身
        // put 不受 WRITE_SECURE_SETTINGS 权限限制）。
        ensureGoogleVis(ctx);

        // 真·overlay：让当前 VoiceInteractionService 直接显示 session UI。
        if (tryShowSession()) return true;

        // SystemUI AssistManager (国行 HyperOS 大概率没接，留作兜底)。
        if (tryStatusBarStartAssist()) return true;

        XposedBridge.log(Constants.TAG
                + " overlay launch failed; skip SearchManager.launchAssist() to avoid Gemini app");
        return false;
    }

    private static void ensureGoogleVis(Context ctx) {
        try {
            android.content.ContentResolver cr = ctx.getContentResolver();
            String cur = android.provider.Settings.Secure.getString(cr,
                    Constants.SECURE_VOICE_INTERACT);
            if (Constants.GSB_ASSIST_SERVICE.equals(cur)) return;
            android.provider.Settings.Secure.putString(cr,
                    Constants.SECURE_VOICE_INTERACT, Constants.GSB_ASSIST_SERVICE);
            XposedBridge.log(Constants.TAG + " runtime forced voice_interaction_service "
                    + cur + " -> " + Constants.GSB_ASSIST_SERVICE);
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " ensureGoogleVis failed: " + t);
        }
    }

    private static boolean tryShowSession() {
        try {
            Class<?> sm = XposedHelpers.findClass("android.os.ServiceManager", null);
            android.os.IBinder binder = (android.os.IBinder) XposedHelpers.callStaticMethod(
                    sm, "getService", "voiceinteraction");
            if (binder == null) return false;
            Class<?> stub = XposedHelpers.findClass(
                    "com.android.internal.app.IVoiceInteractionManagerService$Stub", null);
            Object svc = XposedHelpers.callStaticMethod(stub, "asInterface", binder);
            if (svc == null) return false;
            Object result = invokeBest(svc, "showSessionForActiveService");
            if (result instanceof Boolean && !((Boolean) result)) return false;
            XposedBridge.log(Constants.TAG
                    + " launched via showSessionForActiveService() (overlay path)");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " showSessionForActiveService failed: " + t);
            return false;
        }
    }

    private static android.os.Bundle assistArgs() {
        android.os.Bundle args = new android.os.Bundle();
        args.putInt("invocation_type", 7);              // power-long-press
        args.putString("invocation_source", "POWER_LONG_PRESS");
        args.putBoolean("request_assist_structure", true);
        args.putBoolean("request_assist_screenshot", true);
        return args;
    }

    private static boolean tryStatusBarStartAssist() {
        try {
            Class<?> serviceManager = XposedHelpers.findClass("android.os.ServiceManager", null);
            Object binder = XposedHelpers.callStaticMethod(serviceManager, "getService", "statusbar");
            if (binder == null) return false;
            Class<?> stub = XposedHelpers.findClass(
                    "com.android.internal.statusbar.IStatusBarService$Stub", null);
            Object statusBar = XposedHelpers.callStaticMethod(stub, "asInterface", binder);
            if (statusBar == null) return false;
            Object result = invokeBest(statusBar, "startAssist");
            if (result instanceof Boolean && !((Boolean) result)) return false;
            XposedBridge.log(Constants.TAG
                    + " launched via IStatusBarService.startAssist() (overlay path)");
            return true;
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " statusbar startAssist failed: " + t);
            return false;
        }
    }

    private static Object invokeBest(Object target, String name) throws Throwable {
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (!name.equals(m.getName())) continue;
            if (canBuildArgs(m.getParameterTypes())) {
                best = m;
                break;
            }
        }
        if (best == null) {
            for (java.lang.reflect.Method m : target.getClass().getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                if (canBuildArgs(m.getParameterTypes())) {
                    best = m;
                    break;
                }
            }
        }
        if (best == null) {
            logCandidateMethods(target, name);
            throw new NoSuchMethodError(target.getClass().getName() + "#" + name);
        }
        best.setAccessible(true);
        Object[] args = buildArgs(best.getParameterTypes());
        XposedBridge.log(Constants.TAG + " invoking " + methodSig(best));
        return best.invoke(target, args);
    }

    private static boolean canBuildArgs(Class<?>[] pts) {
        for (Class<?> pt : pts) {
            if (pt == android.os.Bundle.class) continue;
            if (pt == String.class) continue;
            if (pt == int.class || pt == Integer.class) continue;
            if (pt == boolean.class || pt == Boolean.class) continue;
            if (pt == android.os.IBinder.class) continue;
            if (!pt.isPrimitive()) continue;
            return false;
        }
        return true;
    }

    private static Object[] buildArgs(Class<?>[] pts) {
        Object[] args = new Object[pts.length];
        for (int i = 0; i < pts.length; i++) {
            Class<?> pt = pts[i];
            if (pt == android.os.Bundle.class) {
                args[i] = assistArgs();
            } else if (pt == int.class || pt == Integer.class) {
                args[i] = SHOW_POWER_ASSIST_WITH_SCREENSHOT;
            } else if (pt == boolean.class || pt == Boolean.class) {
                args[i] = true;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static void logCandidateMethods(Object target, String name) {
        for (java.lang.reflect.Method m : target.getClass().getMethods()) {
            if (name.equals(m.getName())) {
                XposedBridge.log(Constants.TAG + " candidate " + methodSig(m));
            }
        }
        for (java.lang.reflect.Method m : target.getClass().getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                XposedBridge.log(Constants.TAG + " candidate " + methodSig(m));
            }
        }
    }

    private static String methodSig(java.lang.reflect.Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName()).append("#").append(m.getName()).append("(");
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(pts[i].getName());
        }
        sb.append(")");
        return sb.toString();
    }
}
