package app.local1st.files.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents a locale advertised by Android from silently falling back to English strings. */
class LocalizationResourcesTest {

    @Test
    fun everySupportedLocaleHasTheCompleteDefaultResourceSet() {
        val resDir = File(requireNotNull(System.getProperty("xfiles.repo")), "app/src/main/res")
        val default = readResources(File(resDir, "values/strings.xml"))
        val locales = resDir.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values-") }
            .map { it to File(it, "strings.xml") }
            .filter { (_, file) -> file.isFile }

        assertTrue("At least one translated locale is required", locales.isNotEmpty())
        locales.forEach { (dir, file) ->
            val translated = readResources(file)
            assertEquals("Missing or extra resources in ${dir.name}", default.keys, translated.keys)
            assertEquals("The product name must remain English in ${dir.name}", "XFiles", translated["app_name"])
            default.forEach { (name, base) ->
                assertEquals(
                    "Format placeholders changed for $name in ${dir.name}",
                    placeholders(base),
                    placeholders(requireNotNull(translated[name])),
                )
            }
        }
    }

    private fun readResources(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, String>()
        val root = document.documentElement
        for (index in 0 until root.childNodes.length) {
            val node = root.childNodes.item(index)
            if (node.nodeName !in setOf("string", "plurals")) continue
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            val value = if (node.nodeName == "plurals") {
                buildString {
                    for (itemIndex in 0 until node.childNodes.length) {
                        val item = node.childNodes.item(itemIndex)
                        if (item.nodeName == "item") append(item.textContent)
                    }
                }
            } else {
                node.textContent
            }
            check(result.put(name, value) == null) { "Duplicate resource $name in ${file.path}" }
        }
        return result
    }

    private fun placeholders(value: String): List<String> =
        PLACEHOLDER.findAll(value).map { it.value }.toSet().sorted()

    private companion object {
        val PLACEHOLDER = Regex("%(?:[1-9]\\d*\\$)?[ds]")
    }
}
