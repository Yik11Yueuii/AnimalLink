package org.animallink.gateway;
import java.util.regex.Pattern;
final class AdoptionMiniappGatewayPolicy {
 private static final String ROOT="/auth/wechat/adoption",ID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
 private static final Pattern SUBMIT=Pattern.compile(ROOT+"/listings/"+ID+"/applications"),ITEM=Pattern.compile(ROOT+"/applications/"+ID),WITHDRAW=Pattern.compile(ROOT+"/applications/"+ID+"/withdraw");
 static boolean allowed(String method,String path){return method!=null&&((method.equals("GET")&&(path.equals(ROOT+"/applications")||path.equals(ROOT+"/handovers")||ITEM.matcher(path).matches()))||(method.equals("POST")&&(SUBMIT.matcher(path).matches()||WITHDRAW.matcher(path).matches())));}
 static String scope(String method,String path){if(!allowed(method,path))throw new IllegalArgumentException();return path.equals(ROOT+"/handovers")?"adoption.handover.read":method.equals("GET")?"adoption.application.read":"adoption.application.write";}
 static String downstreamPath(String path){return path.replaceFirst("^/auth/wechat/adoption", "/internal/v1/miniapp/adoption");}
}
