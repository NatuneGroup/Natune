package app.natune.startup

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RestoreContextTest {
    @Test
    fun restoreContextAnswersWithTheApplicationWhileThePlatformStillReturnsNull() {
        val app = RuntimeEnvironment.getApplication()
        // What attachBaseContext sees: a base whose application context is not registered yet.
        val attaching = object : Application() {
            init {
                attachBaseContextForTest(object : ContextWrapper(app.baseContext) {
                    override fun getApplicationContext(): Context? = null
                })
            }
        }
        assertNull(attaching.applicationContext)
        val context = restoreContext(attaching)
        assertSame(attaching, context.applicationContext)
        // DataStore's file lives under filesDir, which the attached base already serves.
        assertEquals(app.filesDir, context.filesDir)
    }
}

private fun Application.attachBaseContextForTest(base: Context) {
    val attach = ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
    attach.isAccessible = true
    attach.invoke(this, base)
}
