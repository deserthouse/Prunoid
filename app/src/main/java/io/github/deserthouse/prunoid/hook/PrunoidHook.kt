package io.github.deserthouse.prunoid.hook

/**
 * 批P：LSPosed 声明式模式（system_server 侧）。
 *
 * 设计（对齐《批 P 立项》）：
 * - hook 点在 intent 解析/查询层（与 IFW 同位，行为可预期；不 hook PMS 内部 scan）
 * - 配置来自 /data/misc/prunoid/declarations.json（主 app 经 root 写入；system_server 可读）
 * - 全局 try-catch 兜底：任何异常吞掉并回退原行为（宁失效不崩 system_server）
 * - 默认关闭：declarations 为空 = 零 hook 效果
 *
 * ⚠️ 未验证不启用：本文件随 APK 分发但声明清单默认为空；
 * 上机验证（含 bootloop 恢复演练）为放出该特性的发布门禁。
 */
class PrunoidHook : de.robv.android.xposed.IXposedHookZygoteInit {

    companion object {
        const val DECLARATIONS_PATH = "/data/misc/prunoid/declarations.json"
        @Volatile private var blockedPrefixes: List<String> = emptyList()
        @Volatile private var enabled: Boolean = false

        /** system_server 侧加载声明（zygote init 后延迟读，避免启动早期 IO） */
        fun loadDeclarations(path: String = DECLARATIONS_PATH) {
            runCatching {
                val text = java.io.File(path).readText()
                val root = org.json.JSONObject(text)  // system_server 无 kotlinx-serialization，走 android 内置
                enabled = root.optBoolean("enabled", false)
                val arr = root.optJSONArray("prefixes") ?: return
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                blockedPrefixes = list
            }.onFailure {
                // 读取失败 = 空配置 = 无效果（安全默认）
                enabled = false
                blockedPrefixes = emptyList()
            }
        }

        /** 组件类名是否命中声明（仅 resolve 层查询用） */
        fun isBlocked(className: String?): Boolean {
            if (!enabled || className == null) return false
            val c = className
            return blockedPrefixes.any { p -> c.startsWith(p) }
        }
    }

    // ── 反射缓存（类加载时一次；hook 热路径零 getDeclaredField）──
    private var fActivityInfo: java.lang.reflect.Field? = null
    private var fServiceInfo: java.lang.reflect.Field? = null
    private var fAiName: java.lang.reflect.Field? = null
    private var fSiName: java.lang.reflect.Field? = null
    @Volatile private var reflectReady = false

    private fun initReflect() {
        if (reflectReady) return
        runCatching {
            val cl = ClassLoader.getSystemClassLoader()
            val ri = cl.loadClass("android.content.pm.ResolveInfo")
            val ai = cl.loadClass("android.content.pm.ActivityInfo")
            val si = cl.loadClass("android.content.pm.ServiceInfo")
            fActivityInfo = ri.getDeclaredField("activityInfo").apply { isAccessible = true }
            fServiceInfo = ri.getDeclaredField("serviceInfo").apply { isAccessible = true }
            fAiName = ai.getDeclaredField("name").apply { isAccessible = true }
            fSiName = si.getDeclaredField("name").apply { isAccessible = true }
            reflectReady = true
        }
    }

    override fun initZygote(startupParam: de.robv.android.xposed.IXposedHookZygoteInit.StartupParam) {
        runCatching {
            initReflect()
            val cl = ClassLoader.getSystemClassLoader()
            val c = cl.loadClass("android.app.ApplicationPackageManager")
            hookAll(c, "queryIntentActivities")
            hookAll(c, "queryIntentServices")
            hookAll(c, "queryIntentReceivers")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { loadDeclarations() }
            }, 30_000)
        }
    }

    private fun hookAll(c: Class<*>, methodName: String) {
        runCatching {
            var n = 0
            for (m in c.declaredMethods) {
                if (m.name != methodName) continue
                if (!List::class.java.isAssignableFrom(m.returnType)) continue
                de.robv.android.xposed.XposedBridge.hookMethod(m, object : de.robv.android.xposed.XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // 热路径：未启用/空声明零开销返回
                        if (!enabled || blockedPrefixes.isEmpty()) return
                        runCatching {
                            @Suppress("UNCHECKED_CAST")
                            val list = param.result as? MutableList<Any?> ?: return
                            if (list.isEmpty()) return
                            val it = list.iterator()
                            while (it.hasNext()) {
                                val ri = it.next() ?: continue
                                if (isBlocked(nameOf(ri))) it.remove()
                            }
                        }
                    }
                })
                n++
            }
            de.robv.android.xposed.XposedBridge.log("PrunoidDecl hooked " + methodName + " x" + n)
        }
    }

    private fun nameOf(ri: Any?): String? {
        if (!reflectReady || ri == null) return null
        return runCatching {
            val act = fActivityInfo?.get(ri)
            if (act != null) fAiName?.get(act) as? String
            else {
                val svc = fServiceInfo?.get(ri)
                if (svc != null) fSiName?.get(svc) as? String else null
            }
        }.getOrNull()
    }
}
