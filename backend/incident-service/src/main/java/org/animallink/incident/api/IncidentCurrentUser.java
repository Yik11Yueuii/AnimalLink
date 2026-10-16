package org.animallink.incident.api;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class IncidentCurrentUser {
  private final RestClient identity;
  private final HttpServletRequest request;

  public IncidentCurrentUser(
      @Qualifier("identityRestClient") RestClient identity,
      HttpServletRequest request) {
    this.identity = identity;
    this.request = request;
  }

  public Principal requireActive() {
    String headerUserId = request.getHeader("X-User-Id");
    if (headerUserId == null || headerUserId.isBlank()) {
      throw new EventController.Unauthorized("需要登录");
    }
    try {
      Map<?, ?> response = identity.get()
          .uri("/api/v1/users/me")
          .header("X-User-Id", headerUserId)
          .retrieve()
          .body(Map.class);
      if (response == null) {
        throw new EventController.Unauthorized("需要登录");
      }
      String userId = value(response.get("id"));
      String accountStatus = value(response.get("accountStatus"));
      if (!headerUserId.equals(userId) || !"ACTIVE".equals(accountStatus)) {
        throw new EventController.Unauthorized("需要登录");
      }
      return new Principal(userId, value(response.get("systemRole")));
    } catch (EventController.Unauthorized exception) {
      throw exception;
    } catch (RestClientResponseException exception) {
      if (exception.getStatusCode().is4xxClientError()) {
        throw new EventController.Unauthorized("需要登录");
      }
      throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");
    } catch (Exception exception) {
      throw new EventController.Dependency("IDENTITY_SERVICE_UNAVAILABLE");
    }
  }

  private static String value(Object value) {
    return value == null ? null : value.toString();
  }

  public record Principal(String id, String systemRole) {}
}
