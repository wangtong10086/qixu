package dev.local.fuxicontrol.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Shared search semantics for the manager and CLI; whitespace-separated terms are ANDed. */
public final class AppSearch {
    public static String permissionText(JSONObject permission) {
        String name=permission.getString("name");
        String aliases="";
        if(name.contains("CAMERA")) aliases=" 相机 摄像头";
        else if(name.contains("RECORD_AUDIO")) aliases=" 麦克风 录音";
        else if(name.contains("LOCATION")) aliases=" 定位 位置";
        else if(name.contains("CONTACTS")) aliases=" 联系人 通讯录";
        else if(name.contains("CALENDAR")) aliases=" 日历";
        else if(name.contains("NOTIFICATIONS")) aliases=" 通知";
        else if(name.contains("SMS")) aliases=" 短信";
        else if(name.contains("CALL_LOG")) aliases=" 通话记录";
        else if(name.contains("PHONE")) aliases=" 电话";
        else if(name.contains("BLUETOOTH")) aliases=" 蓝牙";
        else if(name.contains("STORAGE") || name.contains("MEDIA_")) aliases=" 存储 照片 媒体 文件";
        return name+" "+permission.getString("label")+aliases;
    }
    public static boolean textMatches(String text,String query) {
        String normalized=text.toLowerCase(Locale.ROOT);
        for(String term:query.trim().toLowerCase(Locale.ROOT).split("\\s+"))
            if(!normalized.contains(term)) return false;
        return true;
    }
    public static String policyText(JSONObject policy) {
        StringBuilder text=new StringBuilder(policy.toString());
        text.append(policy.getString("autostart").equals("deny")?" 禁止自启 禁自启":" 允许自启");
        text.append(" 后台 ").append(switch(policy.getString("background")) {
            case "normal" -> "正常"; case "balanced" -> "平衡"; default -> "严格";
        });
        if(policy.getInt("cpuNice")>0 || policy.getInt("uclampMax")<1024) text.append(" CPU 调度 优先级");
        if(policy.getLong("memoryHighMiB")>0 || policy.getLong("memoryMaxMiB")>0) text.append(" 内存 限制");
        if(policy.getLong("uploadBps")>0 || policy.getLong("downloadBps")>0) text.append(" 网络 限速 上传 下载");
        if(!policy.getString("network").equals("allow")) text.append(" 禁止网络 断网");
        if(policy.getJSONObject("permissions").length()>0 || policy.getJSONObject("appOps").length()>0) text.append(" 权限 限制");
        if(policy.getBoolean("resident")) text.append(" 常驻");
        if(policy.optBoolean("protected")) text.append(" 关键服务 保护");
        if(policy.getJSONArray("wakeLocks").length()>0) text.append(" 唤醒锁");
        if(policy.getBoolean("cleanupOnTaskRemoved")) text.append(" 划走 清理");
        return text.toString();
    }
    public static boolean matches(JSONObject app,JSONObject effective,String query,String scope) {
        Policy.oneOf(scope,"all","name","declared","granted","policy");
        StringBuilder text=new StringBuilder();
        if(scope.equals("all") || scope.equals("name")) text.append(app.getString("label")).append(' ').append(app.getString("package"));
        if(scope.equals("all") || scope.equals("policy")) text.append(' ').append(policyText(effective));
        if(scope.equals("all") || scope.equals("declared") || scope.equals("granted")) {
            JSONArray permissions=app.getJSONArray("permissions");
            for(int i=0;i<permissions.length();i++) {
                JSONObject permission=permissions.getJSONObject(i);
                if(!scope.equals("granted") || permission.getBoolean("granted")) text.append(' ').append(permissionText(permission));
            }
        }
        return textMatches(text.toString(),query);
    }
}
