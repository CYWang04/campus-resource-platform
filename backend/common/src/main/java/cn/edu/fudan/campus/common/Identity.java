package cn.edu.fudan.campus.common;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class Identity {
  private final String key;
  private final RestClient client;

  public Identity(
      @Value("${campus.service-key}") String key, @Value("${campus.identity-url}") String url) {
    if (key.length() < 32)
      throw new IllegalArgumentException("SERVICE_KEY must have at least 32 characters");
    this.key = key;
    client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(
                new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                  {
                    setConnectTimeout(3000);
                    setReadTimeout(5000);
                  }
                })
            .build();
  }

  public void internal(String provided) {
    if (provided == null
        || !MessageDigest.isEqual(
            key.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8)))
      throw Api.fail(403, "SERVICE_FORBIDDEN", "服务身份不正确");
  }

  public Actor require(String auth, String permission) {
    if (auth == null || !auth.startsWith("Bearer ")) throw Api.fail(401, "UNAUTHENTICATED", "请先登录");
    Actor a;
    try {
      a =
          client
              .post()
              .uri("/internal/v1/auth/introspect")
              .header("X-Service-Key", key)
              .body(Map.of("token", auth.substring(7)))
              .retrieve()
              .body(Actor.class);
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      throw Api.fail(
          e.getStatusCode().value() == 401 ? 401 : 503, "IDENTITY_REJECTED", "登录已失效或身份校验失败");
    } catch (org.springframework.web.client.RestClientException e) {
      throw Api.fail(503, "IDENTITY_UNAVAILABLE", "身份服务暂不可用");
    }
    if (a == null) throw Api.fail(503, "IDENTITY_UNAVAILABLE", "身份服务响应异常");
    if (permission != null && !a.permissions().contains(permission))
      throw Api.fail(403, "FORBIDDEN", "没有此操作权限");
    return a;
  }

  public String key() {
    return key;
  }

  public record Actor(
      String id, String username, String displayName, String email, String departmentCode, List<String> permissions) {}
}
