package app.natune

import android.os.Build

actual val platformBlurSdkLevel: Int get() = Build.VERSION.SDK_INT
