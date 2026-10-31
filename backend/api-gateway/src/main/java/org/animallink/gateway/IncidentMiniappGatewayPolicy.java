package org.animallink.gateway;

import java.util.regex.Pattern;
import org.springframework.http.HttpMethod;

final class IncidentMiniappGatewayPolicy {
    static final String SCOPE = "incident.rescue-draft.write";
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private static final Pattern COLLECTION = Pattern.compile("^/auth/wechat/rescue-drafts$");
    private static final Pattern ITEM = Pattern.compile("^/auth/wechat/rescue-drafts/" + UUID + "$");
    private static final Pattern SUBMIT = Pattern.compile("^/auth/wechat/rescue-drafts/" + UUID + "/submit$");
    private IncidentMiniappGatewayPolicy() { }
    static boolean allowed(String method, String path) { return (HttpMethod.POST.name().equals(method) && (COLLECTION.matcher(path).matches() || SUBMIT.matcher(path).matches())) || ((HttpMethod.GET.name().equals(method) || HttpMethod.PATCH.name().equals(method)) && ITEM.matcher(path).matches()); }
    static String downstreamPath(String path) { if (!path.startsWith("/auth/wechat/rescue-drafts")) throw new IllegalArgumentException(); return "/api/v1/internal/miniapp/rescue-drafts" + path.substring("/auth/wechat/rescue-drafts".length()); }
}
