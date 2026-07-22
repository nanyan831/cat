package com.example.catlifepet.floating

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.catlifepet.util.NotificationUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundServiceContractInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun manifestDeclaresNotificationAndForegroundServiceRequirements() {
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(
                Manifest.permission.FOREGROUND_SERVICE,
                context.packageName
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val requested = context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                .orEmpty()
            assertTrue(requested.contains(Manifest.permission.POST_NOTIFICATIONS))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val serviceInfo = context.packageManager.getServiceInfo(
                ComponentName(context, CatFloatingService::class.java),
                PackageManager.ComponentInfoFlags.of(0)
            )
            assertTrue(
                serviceInfo.foregroundServiceType and
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0
            )
        }
    }

    @Test
    fun notificationCapabilityCheckIsSafeOnCurrentPlatform() {
        NotificationUtils.canPostNotifications(context)
    }
}
