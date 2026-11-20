package app.natune.utils

import java.io.IOException

class UpdateDownloadHttpException(val statusCode: Int) : IOException("Update download failed: HTTP $statusCode")
