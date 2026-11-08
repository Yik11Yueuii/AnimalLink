package org.animallink.gateway;
import java.util.regex.Pattern;
final class IntelligenceMiniappGatewayPolicy {
 private static final String UUID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
 private static final Pattern CREATE=Pattern.compile("^/auth/wechat/observations/(parse|manual|upload-url)$"), ITEM=Pattern.compile("^/auth/wechat/observations/"+UUID+"$"), CONFIRM=Pattern.compile("^/auth/wechat/observations/"+UUID+"/confirm$");
 static boolean allowed(String method,String path){return path!=null&&(("POST".equals(method)&&(CREATE.matcher(path).matches()||CONFIRM.matcher(path).matches()))||("GET".equals(method)&&ITEM.matcher(path).matches()));}
 static String scope(String method,String path){return "GET".equals(method)?"intelligence.observation.read":"intelligence.observation.write";}
 static String downstreamPath(String path){return "/internal/v1/miniapp/observations"+path.substring("/auth/wechat/observations".length());}
}
