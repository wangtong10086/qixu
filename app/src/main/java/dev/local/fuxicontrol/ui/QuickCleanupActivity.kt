package dev.local.fuxicontrol.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** LauncherApps starts shortcuts as the owning package; this activity is not exported. */
class QuickCleanupActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(intent.action!="dev.local.fuxicontrol.CLEANUP"){finish();return}
        // Restoring an activity must never submit the destructive action a second time.
        setContent {QixuTheme(window){
            var message by remember{mutableStateOf("正在清理后台…")}
            var complete by remember{mutableStateOf(false)}
            var result by remember{mutableStateOf<JSONObject?>(null)}
            var details by remember{mutableStateOf(false)}
            LaunchedEffect(Unit){
                try {
                    val request=JSONObject().put("command",if(savedInstanceState==null)"cleanup.quick" else "cleanup.status")
                    result=CleanupClient.execute(request)
                    message=CleanupClient.summary(result!!)
                }catch(e:java.io.IOException){message=e.message?:"服务未连接"}
                complete=true
            }
            Surface(Modifier.fillMaxSize()) {Column(Modifier.padding(24.dp).statusBarsPadding().navigationBarsPadding(),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Text("一键清理",style=MaterialTheme.typography.headlineSmall)
                if(!complete)LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(message)
                if(result!=null)TextButton(onClick={details=!details}){Text(if(details)"收起结果" else "查看结果")}
                if(details)androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f)){
                    val rows=result?.optJSONArray("results")?:org.json.JSONArray()
                    items(rows.length()){i->val row=rows.getJSONObject(i)
                        val state=when(row.optString("state")){"exited"->"已退出";"requested"->"已请求结束";"skipped"->"已跳过";else->"失败"}
                        ListItem(headlineContent={Text(row.getString("package"))},supportingContent={Text("$state ${row.optString("reason")}")})
                    }
                }
                TextButton(onClick={finish()}){Text("返回")}
            }}
        }}
    }
}
