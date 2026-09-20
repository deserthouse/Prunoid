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

    override fun initZygote(startupParam: de.robv.android.xposed.IXposedHookZygoteInit.StartupParam) {
        // 骨架：真正的 resolve 层 hook 点绑定在 P 批上机阶段实现并验证
        // （需要真机 LSPosed 环境逐点验证后启用；当前仅提供配置读取与匹配逻辑）
        runCatching { loadDeclarations() }
    }
}
