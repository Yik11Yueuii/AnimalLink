package org.animallink.gateway;

import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;

final class IncidentMiniappGatewayPolicy {
    static final String SCOPE = "incident.rescue-draft.write";
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern COLLECTION = Pattern.compile("^/auth/wechat/rescue-drafts$");
    private static final Pattern ITEM = Pattern.compile("^/auth/wechat/rescue-drafts/" + UUID + "$");
    private static final Pattern SUBMIT = Pattern.compile("^/auth/wechat/rescue-drafts/" + UUID + "/submit$");
    private static final Pattern CLAIM = Pattern.compile("^/auth/wechat/rescues/" + UUID + "/claim$");
    private static final Pattern ACTIONS = Pattern.compile("^/auth/wechat/cases/" + UUID + "/actions$");
    private static final Pattern CASE_BY_EVENT = Pattern.compile("^/auth/wechat/rescues/" + UUID + "/case$");
    private static final Pattern RESULT = Pattern.compile("^/auth/wechat/cases/" + UUID + "/result$");
    private static final Pattern CLOSE = Pattern.compile("^/auth/wechat/cases/" + UUID + "/close$");
    private IncidentMiniappGatewayPolicy() { }
    static boolean allowed(String method, String path) { return (HttpMethod.POST.name().equals(method) && (RESULT.matcher(path).matches() || CLOSE.matcher(path).matches())) || (HttpMethod.GET.name().equals(method) && CASE_BY_EVENT.matcher(path).matches()) || ((HttpMethod.GET.name().equals(method) || HttpMethod.POST.name().equals(method)) && ACTIONS.matcher(path).matches()) || (HttpMethod.POST.name().equals(method) && (COLLECTION.matcher(path).matches() || SUBMIT.matcher(path).matches() || CLAIM.matcher(path).matches())) || ((HttpMethod.GET.name().equals(method) || HttpMethod.PATCH.name().equals(method)) && ITEM.matcher(path).matches()); }
    static String scope(String method, String path) { return RESULT.matcher(path).matches() ? "incident.case-result.write" : CLOSE.matcher(path).matches() ? "incident.case-close.write" : CASE_BY_EVENT.matcher(path).matches() ? "incident.case-action.read" : ACTIONS.matcher(path).matches() ? ("GET".equals(method) ? "incident.case-action.read" : "incident.case-action.write") : CLAIM.matcher(path).matches() ? "incident.case.claim" : SCOPE; }
    static String downstreamPath(String path) { if (CASE_BY_EVENT.matcher(path).matches()) return "/api/v1/internal/miniapp/rescues" + path.substring("/auth/wechat/rescues".length()); if (RESULT.matcher(path).matches() || CLOSE.matcher(path).matches() || ACTIONS.matcher(path).matches()) return "/api/v1/internal/miniapp/cases" + path.substring("/auth/wechat/cases".length()); if (CLAIM.matcher(path).matches()) return "/api/v1/internal/miniapp/rescues" + path.substring("/auth/wechat/rescues".length()); if (!path.startsWith("/auth/wechat/rescue-drafts")) throw new IllegalArgumentException(); return "/api/v1/internal/miniapp/rescue-drafts" + path.substring("/auth/wechat/rescue-drafts".length()); }
}
