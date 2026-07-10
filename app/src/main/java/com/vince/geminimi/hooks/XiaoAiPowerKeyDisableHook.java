package com.vince.geminimi.hooks;

import com.vince.geminimi.Constants;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 阻止 com.miui.voiceassist 在开机时把"电源键唤醒小爱"重新置位。
 * 教程第一步等价于在它的设置 SharedPreferences 里把 power_wakeup 关掉，
 * 这里直接 hook 读取该 preference 的方法，让它永远返回 false。
 */
public final class XiaoAiPowerKeyDisableHook {

    private XiaoAiPowerKeyDisableHook() {}
    public static void apply(XC_LoadPackage.LoadPackageParam lpp) {
        // SharedPreferences / Editor 是接口，hook 会抛 "Cannot hook abstract methods"。
        // 真正的实现类是 SharedPreferencesImpl(.EditorImpl)，它们是 framework 自带，
        // 在所有进程的 boot class loader 里，所以 lpp.classLoader 也能拿到。
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl$EditorImpl",
                    lpp.classLoader,
                    "putBoolean", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];
                            if (HookPolicy.isPowerWakeupKey(key)) {
                                param.args[1] = false;
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " XiaoAi prefs hook failed: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.SharedPreferencesImpl",
                    lpp.classLoader,
                    "getBoolean", String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];
                            if (HookPolicy.isPowerWakeupKey(key)) {
                                param.setResult(false);
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(Constants.TAG + " XiaoAi getBoolean hook failed: " + t);
        }
    }
}
