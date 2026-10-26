package app.zmby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportGuardTest {
    @Test fun `five wrong codes lock that source only, and the lock expires`() {
        var now = 0L
        val guard = ImportGuard({ now }, perSource = 5, lockMs = 600_000, total = 20)
        repeat(4) { assertEquals(ImportGuard.Verdict.Wrong(4 - it), guard.check("a", false)) }
        assertEquals(ImportGuard.Verdict.Locked(600_000), guard.check("a", false))
        assertEquals(ImportGuard.Verdict.Locked(600_000), guard.check("a", true))
        assertEquals(ImportGuard.Verdict.Ok, guard.check("b", true))
        now = 600_001
        assertNull(guard.locked("a"))
        assertEquals(ImportGuard.Verdict.Ok, guard.check("a", true))
    }

    @Test fun `a right code clears the source's count`() {
        val guard = ImportGuard({ 0 }, perSource = 3, total = 20)
        guard.check("a", false); guard.check("a", false)
        assertEquals(ImportGuard.Verdict.Ok, guard.check("a", true))
        assertEquals(ImportGuard.Verdict.Wrong(2), guard.check("a", false))
    }

    @Test fun `wrong codes from many sources close the session`() {
        val guard = ImportGuard({ 0 }, perSource = 5, total = 6)
        repeat(5) { assertTrue(guard.check("10.0.0.$it", false) is ImportGuard.Verdict.Wrong) }
        assertEquals(ImportGuard.Verdict.Closed, guard.check("10.0.0.9", false))
        assertEquals(ImportGuard.Verdict.Closed, guard.check("10.0.0.10", true))
    }

    @Test fun `codes compare whole`() {
        assertTrue(ImportGuard.same("123456", "123456"))
        assertTrue(!ImportGuard.same("123456", "12345"))
    }
}
