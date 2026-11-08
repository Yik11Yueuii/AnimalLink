package org.animallink.intelligence.infrastructure;
import java.util.Optional;
import java.util.regex.Pattern;
final class IntelligenceMiniappRequestPolicy {
 private static final String UUID="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
 private static final Pattern CREATE=Pattern.compile("^/internal/v1/miniapp/observations/(parse|manual|upload-url)$");
 private static final Pattern ITEM=Pattern.compile("^/internal/v1/miniapp/observations/"+UUID+"$");
 private static final Pattern CONFIRM=Pattern.compile("^/internal/v1/miniapp/observations/"+UUID+"/confirm$");
 private static final Pattern MATCH=Pattern.compile("^/internal/v1/miniapp/observations/"+UUID+"/match-candidates$"), TASK_DECISION=Pattern.compile("^/internal/v1/miniapp/observations/"+UUID+"/decision$"), MATCH_ITEM=Pattern.compile("^/internal/v1/miniapp/matches/"+UUID+"$"), MATCH_DECISION=Pattern.compile("^/internal/v1/miniapp/matches/"+UUID+"/decision$"), FINALIZE=Pattern.compile("^/internal/v1/miniapp/matches/"+UUID+"/finalize$");
 static boolean isProtected(String path){return path!=null&&(CREATE.matcher(path).matches()||ITEM.matcher(path).matches()||CONFIRM.matcher(path).matches()||MATCH.matcher(path).matches()||TASK_DECISION.matcher(path).matches()||MATCH_ITEM.matcher(path).matches()||MATCH_DECISION.matcher(path).matches()||FINALIZE.matcher(path).matches());}
 static Optional<String> requiredScope(String method,String path){if(path==null)return Optional.empty();if("GET".equals(method)&&(TASK_DECISION.matcher(path).matches()||MATCH_ITEM.matcher(path).matches()||MATCH_DECISION.matcher(path).matches()))return Optional.of("intelligence.match.read");if("POST".equals(method)&&(MATCH.matcher(path).matches()||FINALIZE.matcher(path).matches()))return Optional.of("intelligence.match.write");if("GET".equals(method)&&ITEM.matcher(path).matches())return Optional.of("intelligence.observation.read");if("POST".equals(method)&&(CREATE.matcher(path).matches()||CONFIRM.matcher(path).matches()))return Optional.of("intelligence.observation.write");return Optional.empty();}
}
