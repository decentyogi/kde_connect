package org.kde.kdeconnect

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.kde.kdeconnect.ui.MainActivity

@RunWith(AndroidJUnit4::class)
@Config(application = KdeConnect::class)
class AppLaunchTest {
    @Test
    fun testAppLaunch() {
        val app = ApplicationProvider.getApplicationContext<KdeConnect>()
        println("App created successfully: $app")
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        println("MainActivity created successfully: ${controller.get()}")
    }
}
