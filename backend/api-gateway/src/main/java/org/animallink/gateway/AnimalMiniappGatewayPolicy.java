package org.animallink.gateway;
import java.util.regex.Pattern;
public final class AnimalMiniappGatewayPolicy {
 private static final String ROOT="/auth/wechat/community/posts";
 private static final Pattern POST=Pattern.compile(ROOT+"/[0-9a-fA-F-]{36}/(?:comments|like)");
 private AnimalMiniappGatewayPolicy() {}
 static boolean allowed(String method,String path){if(method==null)return false;if(path.equals(ROOT))return method.equals("POST");if(!POST.matcher(path).matches())return false;return path.endsWith("/comments")?method.equals("POST"):method.equals("GET")||method.equals("POST")||method.equals("DELETE");}
 static String scope(String method,String path){if(!allowed(method,path))throw new IllegalArgumentException();return method.equals("GET")?"animal.community.read":"animal.community.write";}
 static String downstreamPath(String path){return path.replaceFirst("^/auth/wechat/community/","/internal/v1/miniapp/community/");}
}
