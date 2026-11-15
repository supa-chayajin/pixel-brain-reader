package cloud.wafflecommons.pixelbrainreader.data.privatevault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A new password for the private journal: at least 12 characters, nothing else (owner's D4). */
class PasswordRulesTest {

    @Test
    fun `twelve characters and a matching repeat are enough`() {
        assertNull(PasswordRules.problem("old password", "twelve chars", "twelve chars"))
        assertNull(PasswordRules.problem("old password", "trois mots longs", "trois mots longs"))
    }

    @Test
    fun `each refusal says why`() {
        assertEquals("Type the current password.", PasswordRules.problem("", "twelve chars", "twelve chars"))
        assertEquals("The new password needs at least 12 characters.", PasswordRules.problem("old", "eleven char", "eleven char"))
        assertEquals("The two new passwords differ.", PasswordRules.problem("old", "twelve chars", "twelve charz"))
        assertEquals("The new password is the current one.", PasswordRules.problem("twelve chars", "twelve chars", "twelve chars"))
    }
}
