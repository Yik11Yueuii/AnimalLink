package org.animallink.adoption.infrastructure;
import java.util.Optional;import java.util.regex.Pattern;
public final class AdoptionMiniappRequestPolicy {
 private static final String ROOT="/internal/v1/miniapp/adoption",ID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
 private static final Pattern SUBMIT=Pattern.compile(ROOT+"/listings/"+ID+"/applications"),ITEM=Pattern.compile(ROOT+"/applications/"+ID),WITHDRAW=Pattern.compile(ROOT+"/applications/"+ID+"/withdraw");
 public static boolean isProtected(String path){return path.equals(ROOT)||path.startsWith(ROOT+"/");}
 public static Optional<String> requiredScope(String method,String path){if(method.equals("GET")&&path.equals(ROOT+"/handovers"))return Optional.of("adoption.handover.read");if(method.equals("GET")&&(path.equals(ROOT+"/applications")||ITEM.matcher(path).matches()))return Optional.of("adoption.application.read");if(method.equals("POST")&&(SUBMIT.matcher(path).matches()||WITHDRAW.matcher(path).matches()))return Optional.of("adoption.application.write");return Optional.empty();}
}
