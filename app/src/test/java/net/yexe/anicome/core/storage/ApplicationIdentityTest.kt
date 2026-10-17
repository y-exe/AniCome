package net.yexe.anicome

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class ApplicationIdentityTest {
    @Test fun manifestComponentsResolveInTheApplicationNamespace() {
        assertEquals("net.yexe.anicome", BuildConfig.APPLICATION_ID)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        for (tag in listOf("application", "activity", "service")) {
            val nodes = manifest.getElementsByTagName(tag)
            for (index in 0 until nodes.length) {
                val name = (nodes.item(index) as Element).getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                val qualifiedName = if (name.startsWith('.')) BuildConfig.APPLICATION_ID + name else name
                assertTrue(qualifiedName, qualifiedName.startsWith(BuildConfig.APPLICATION_ID + "."))
                assertNotNull(Class.forName(qualifiedName))
            }
        }
        assertEquals(0, manifest.getElementsByTagName("activity-alias").length)
    }
}
