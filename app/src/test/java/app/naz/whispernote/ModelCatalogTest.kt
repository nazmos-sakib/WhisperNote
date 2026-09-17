package app.naz.whispernote

import app.naz.whispernote.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelCatalogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oldModelIdsStayStandardAndEachFamilyHasACompactVariant() {
        for (family in listOf("tiny", "base", "small")) {
            val standard = ModelCatalog.get(family)
            val compact = ModelCatalog.variant(family, true)
            assertFalse(standard.quantized)
            assertEquals("$family-q5_1", compact.id)
            assertTrue(compact.bytes < standard.bytes / 2)
            assertEquals(64, compact.sha256.length)
        }
    }

    @Test fun switchingFamilyKeepsFormat() {
        val chosen = ModelCatalog.get("small-q5_1")
        assertEquals("base-q5_1", ModelCatalog.variant("base", chosen.quantized).id)
        assertEquals("small", ModelCatalog.variant(chosen.family, false).id)
    }

    @Test fun validModelIsPublishedOnlyAfterVerification() = runBlocking {
        val bytes = "fixture model bytes".toByteArray()
        val staging = temporary.newFile("staged").apply { writeBytes(bytes) }
        val target = temporary.root.resolve("model.bin")
        val model = Model("fixture", "tiny", true, bytes.size.toLong(), sha256(bytes))
        ModelFiles.verifyAndPublish(staging, target, model)
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(staging.exists())
    }

    @Test fun corruptModelCannotReplaceExistingModel() = runBlocking {
        val target = temporary.newFile("model.bin").apply { writeText("valid existing data") }
        val staging = temporary.newFile("staged").apply { writeText("corrupt") }
        val model = Model("fixture", "tiny", true, staging.length(), "0".repeat(64))
        try { ModelFiles.verifyAndPublish(staging, target, model); fail("Expected integrity failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals("valid existing data", target.readText())
        assertFalse(staging.exists())
    }

    @Test fun truncatedModelIsNeverPublished() = runBlocking {
        val staging = temporary.newFile("staged").apply { writeText("short") }
        val target = temporary.root.resolve("model.bin")
        try {
            ModelFiles.verifyAndPublish(staging, target, Model("fixture", "tiny", true, 100, "0".repeat(64)))
            fail("Expected size failure")
        } catch (_: IllegalArgumentException) { }
        assertFalse(target.exists())
        assertFalse(staging.exists())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
