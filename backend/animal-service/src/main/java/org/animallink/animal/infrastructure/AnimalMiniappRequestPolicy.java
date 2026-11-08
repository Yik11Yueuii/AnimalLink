package org.animallink.animal.infrastructure;
import java.util.Optional;
import java.util.regex.Pattern;
public final class AnimalMiniappRequestPolicy {
 private static final String ROOT="/internal/v1/miniapp/community/posts";
 private static final Pattern POST=Pattern.compile(ROOT+"/[0-9a-fA-F-]{36}/(?:comments|like)");
 private AnimalMiniappRequestPolicy() {}
 public static boolean isProtected(String path){return path.equals(ROOT)||path.startsWith(ROOT+"/");}
 public static Optional<String> requiredScope(String method,String path){
  if(path.equals(ROOT)&&method.equals("POST"))return Optional.of("animal.community.write");
  if(!POST.matcher(path).matches())return Optional.empty();
  if(path.endsWith("/comments")&&method.equals("POST"))return Optional.of("animal.community.write");
  if(path.endsWith("/like")&&(method.equals("GET")||method.equals("POST")||method.equals("DELETE")))return Optional.of(method.equals("GET")?"animal.community.read":"animal.community.write");
  return Optional.empty();
 }
}
