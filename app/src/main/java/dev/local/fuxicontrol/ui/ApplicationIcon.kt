package dev.local.fuxicontrol.ui

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.local.fuxicontrol.R
import dev.local.fuxicontrol.service.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Limit decoded icons and in-flight requests independently of the installed app count. */
internal class AppIcons {
    private val cache = LruCache<String, ImageBitmap>(64)
    private val requests = Semaphore(3)

    suspend fun load(name: String, updated: Long): ImageBitmap {
        val key = "$name:$updated"
        cache.get(key)?.let {
            return it
        }
        return requests.withPermit {
            withContext(Dispatchers.IO) {
                val response =
                    Wire.call(JSONObject().put("command", "app.icon").put("package", name))
                val data = Base64.decode(response.getString("png"), Base64.DEFAULT)
                val bitmap =
                    checkNotNull(BitmapFactory.decodeByteArray(data, 0, data.size)) {
                            "Invalid application icon PNG"
                        }
                        .asImageBitmap()
                cache.put(key, bitmap)
                bitmap
            }
        }
    }
}

@Composable
internal fun ApplicationIcon(app: JSONObject, icons: AppIcons) {
    val name = app.getString("package")
    val updated = app.getLong("lastUpdateTime")
    var bitmap by remember(name, updated) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(name, updated) {
        bitmap =
            try {
                icons.load(name, updated)
            } catch (_: java.io.IOException) {
                // A disconnected service or removed package shows the labeled placeholder.
                null
            }
    }
    val current = bitmap
    if (current != null) Image(current, "${app.getString("label")}图标", Modifier.size(40.dp))
    else
        Icon(
            painterResource(R.drawable.ic_grid_2x2),
            "应用图标暂不可用",
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
}
