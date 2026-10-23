package dev.local.fuxicontrol.ui

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import dev.local.fuxicontrol.service.Wire
import kotlinx.coroutines.*
import org.json.JSONObject

class CleanupTileService:TileService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var busy=false
    override fun onStartListening(){super.onStartListening();refresh()}
    private fun tile(text:String,available:Boolean=true){qsTile?.let{it.label="一键清理";it.subtitle=text;it.state=if(available)Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE;it.updateTile()}}
    private fun refresh(){scope.launch {
        try {
            val status=withContext(Dispatchers.IO){Wire.call(JSONObject().put("command","status"))}
            tile(if(busy)"清理中" else if(status.optString("mode")=="enforce")"就绪" else "仅观察或已停用",!busy&&status.optString("mode")=="enforce")
        }catch(e:java.io.IOException){tile("服务未连接",false)}
    }}
    override fun onClick(){super.onClick();if(busy)return
        unlockAndRun{scope.launch {
            if(busy)return@launch
            busy=true;tile("清理中",false)
            try {Toast.makeText(this@CleanupTileService,CleanupClient.summary(CleanupClient.execute(JSONObject().put("command","cleanup.quick"))),Toast.LENGTH_LONG).show()}
            catch(e:java.io.IOException){Toast.makeText(this@CleanupTileService,e.message?:"清理失败",Toast.LENGTH_LONG).show()}
            finally{busy=false;refresh()}
        }}
    }
    override fun onDestroy(){scope.cancel();super.onDestroy()}
}
