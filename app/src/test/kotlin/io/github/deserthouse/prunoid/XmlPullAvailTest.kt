package io.github.deserthouse.prunoid

import org.junit.Assert.assertTrue
import org.junit.Test

class XmlPullAvailTest {
    @Test
    fun `xmlpull factory 在 JVM 可用`() {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(java.io.StringReader("<rules><activity><component-filter name=\"com.p/A\"/></activity></rules>"))
        var event = parser.eventType
        var found = false
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "component-filter") {
                found = true
                break
            }
            event = parser.next()
        }
        assertTrue(found)
    }
}
