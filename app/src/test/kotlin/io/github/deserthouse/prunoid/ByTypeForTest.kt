package io.github.deserthouse.prunoid

import io.github.deserthouse.prunoid.core.rules.ComponentAnchor
import io.github.deserthouse.prunoid.core.rules.Safety
import io.github.deserthouse.prunoid.core.scanner.ScannedApp
import io.github.deserthouse.prunoid.core.scanner.SdkHit
import io.github.deserthouse.prunoid.ui.byTypeForVM
import org.junit.Assert.assertEquals
import org.junit.Test

/** 批债33：byTypeFor 纯函数（app 勾选 → 引擎类型分组）抽验 */
class ByTypeForTest {
    private fun hit(id: String, comps: List<Pair<String, String>>) = SdkHit(
        ruleId = id, name = id, category = "ads", safety = Safety.SAFE,
        matchedComponents = comps.map { it.first },
        componentTypes = comps.associate { it.first to it.second }
    )

    @Test
    fun `按类型分组且只含选中规则`() {
        val app = ScannedApp(
            packageName = "p", label = "P", isSystem = false,
            matchedSdks = listOf(
                hit("a", listOf("com.a.A1" to "activity", "com.a.S1" to "service")),
                hit("b", listOf("com.b.R1" to "receiver"))
            )
        )
        val out = io.github.deserthouse.prunoid.ui.byTypeForVM(app, setOf("a"))
        assertEquals(mapOf("activity" to listOf("com.a.A1"), "service" to listOf("com.a.S1")), out)
    }

    @Test
    fun `空选择返回空`() {
        val app = ScannedApp(packageName = "p", label = "P", isSystem = false, matchedSdks = listOf(hit("a", listOf("com.a.A1" to "activity"))))
        assertEquals(emptyMap<String, List<String>>(), io.github.deserthouse.prunoid.ui.byTypeForVM(app, emptySet()))
    }
}
