package cn.edu.fudan.campus.common;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class SystemController {
  private final String name;
  private final Identity identity;

  public SystemController(@Value("${spring.application.name}") String name, Identity identity) {
    this.name = name;
    this.identity = identity;
  }

  @GetMapping("/api/v1/system/{service}/version")
  public Object version(
      @PathVariable String service,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    identity.require(auth, null);
    if (!service.equals(name)) throw Api.fail(404, "SERVICE_NOT_FOUND", "服务不存在");
    return Api.ok(Map.of("service", name, "version", "0.1.0-SNAPSHOT"));
  }
}
