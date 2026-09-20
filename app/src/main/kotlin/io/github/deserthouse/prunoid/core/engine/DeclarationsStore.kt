package io.github.deserthouse.prunoid.core.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 批P：声明式配置通道。
 * 主 app（root）写 /data/misc/prunoid/declarations.json；system_server 侧模块只读。
 * 目录 755 / 文件 644（SELinux 未写策略——上机验证阶段确认 contexts，必要时 magiskpolicy 补规则）。
 * 默认 enabled=false：装模块不改任何行为，用户在 app 里显式画圈才生效。
 */
object DeclarationsStore {

    private const val DIR = "/data/misc/prunoid"
    private const val FILE = "$DIR/declarations.json"

    /** 当前声明的 SDK 前缀集合（内存缓存；读文件需 root，app 侧启动时拉一次） */
    @Volatile var declaredPrefixes: Set<String> = emptySet()
        private set
    @Volatile var enabled: Boolean = false
        private set

    data class Decl(val enabled: Boolean, val prefixes: List<String>)

    /** root 写声明（由主 app 调用；prefixes = 各规则 packPrefixes 的并集展开） */
    suspend fun write(decl: Decl): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildString {
                append("{").append('"').append("enabled").append("\":").append(decl.enabled)
                    .append(",\"prefixes\":[")
                decl.prefixes.forEachIndexed { i, p ->
                    if (i > 0) append(",")
                    append("\"").append(p.replace("\"", "")).append("\"")
                }
                append("]}")
            }
            val sh = com.topjohnwu.superuser.Shell.cmd(
                "mkdir -p $DIR && chmod 755 $DIR",
                "cat > $FILE <<'PRUNOID_EOF'\n$json\nPRUNOID_EOF",
                "chmod 644 $FILE"
            ).exec()
            check(sh.isSuccess) { "shell write failed: ${sh.err}" }
            enabled = decl.enabled
            declaredPrefixes = decl.prefixes.toSet()
        }
    }

    /** root 读回当前声明（启动时恢复 UI 状态） */
    suspend fun read(): Result<Decl> = withContext(Dispatchers.IO) {
        runCatching {
            val out = com.topjohnwu.superuser.Shell.cmd("cat $FILE 2>/dev/null").exec().out
                .joinToString("")
            if (out.isBlank()) return@runCatching Decl(false, emptyList())
            val root = org.json.JSONObject(out)
            val arr = root.optJSONArray("prefixes")
            val list = ArrayList<String>()
            if (arr != null) for (i in 0 until arr.length()) list.add(arr.getString(i))
            Decl(root.optBoolean("enabled", false), list).also {
                enabled = it.enabled
                declaredPrefixes = it.prefixes.toSet()
            }
        }
    }
}
