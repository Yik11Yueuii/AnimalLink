package org.animallink.gateway;
import java.util.regex.Pattern;
final class IntelligenceMiniappGatewayPolicy {
 private static final String UUID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
 private static final Pattern CREATE=Pattern.compile("^/auth/wechat/observations/(parse|manual|upload-url)$"), ITEM=Pattern.compile("^/auth/wechat/observations/"+UUID+"$"), CONFIRM=Pattern.compile("^/auth/wechat/observations/"+UUID+"/confirm$");
 private static final Pattern MATCH=Pattern.compile("^/auth/wechat/observations/"+UUID+"/match-candidates$"), TASK_DECISION=Pattern.compile("^/auth/wechat/observations/"+UUID+"/decision$"), MATCH_ITEM=Pattern.compile("^/auth/wechat/matches/"+UUID+"$"), MATCH_DECISION=Pattern.compile("^/auth/wechat/matches/"+UUID+"/decision$"), FINALIZE=Pattern.compile("^/auth/wechat/matches/"+UUID+"/finalize$");
 static boolean allowed(String method,String path){return path!=null&&(("POST".equals(method)&&(CREATE.matcher(path).matches()||CONFIRM.matcher(path).matches()||MATCH.matcher(path).matches()||FINALIZE.matcher(path).matches()))||("GET".equals(method)&&(ITEM.matcher(path).matches()||TASK_DECISION.matcher(path).matches()||MATCH_ITEM.matcher(path).matches()||MATCH_DECISION.matcher(path).matches())));}
 static String scope(String method,String path){return MATCH.matcher(path).matches()||FINALIZE.matcher(path).matches()?"intelligence.match.write":TASK_DECISION.matcher(path).matches()||MATCH_ITEM.matcher(path).matches()||MATCH_DECISION.matcher(path).matches()?"intelligence.match.read":"GET".equals(method)?"intelligence.observation.read":"intelligence.observation.write";}
 static String downstreamPath(String path){return path.startsWith("/auth/wechat/matches/")?"/internal/v1/miniapp/matches"+path.substring("/auth/wechat/matches".length()):"/internal/v1/miniapp/observations"+path.substring("/auth/wechat/observations".length());}
}
