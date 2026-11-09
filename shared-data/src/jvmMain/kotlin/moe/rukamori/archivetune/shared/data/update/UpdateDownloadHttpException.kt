package moe.rukamori.archivetune.shared.data.update

import java.io.IOException

class UpdateDownloadHttpException(val statusCode: Int) : IOException("Update download failed: HTTP $statusCode")
