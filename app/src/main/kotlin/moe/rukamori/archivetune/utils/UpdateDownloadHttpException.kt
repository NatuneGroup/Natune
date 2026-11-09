package moe.rukamori.archivetune.utils

import java.io.IOException

class UpdateDownloadHttpException(val statusCode: Int) : IOException("Update download failed: HTTP $statusCode")
