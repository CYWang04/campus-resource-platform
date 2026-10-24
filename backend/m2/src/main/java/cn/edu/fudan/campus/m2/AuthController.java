package cn.edu.fudan.campus.m2;

import cn.edu.fudan.campus.common.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class AuthController {
  private final JdbcTemplate db;
  private final Identity identity;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final String dummy = encoder.encode("invalid-password");

  public AuthController(JdbcTemplate db, Identity identity) {
    this.db = db;
    this.identity = identity;
  }

  @Bean
  ApplicationRunner seed(@Value("${campus.demo-password}") String password) {
    return args -> {
      if (password.length() < 12)
        throw new IllegalArgumentException("DEMO_PASSWORD must have at least 12 characters");
      for (String name : List.of("student", "manager", "technician")) {
        String id =
            UUID.nameUUIDFromBytes(
                    ("demo:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        db.update(
            "INSERT INTO users(id,username,display_name,email,password_hash) VALUES(?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),"
                + " email=VALUES(email),password_hash=VALUES(password_hash),enabled=TRUE",
            id,
            name,
            Map.of("student", "演示学生", "manager", "资源管理员", "technician", "维修人员").get(name),
            name + "@campus.test",
            encoder.encode(password));
        for (String permission :
            switch (name) {
              case "manager" ->
                  List.of(
                      "resources:read",
                      "resources:manage",
                      "reservations:stats",
                      "workorders:create",
                      "workorders:read",
                      "workorders:accept",
                      "workorders:assign",
                      "roomapplications:read",
                      "roomapplications:approve:department",
                      "roomapplications:approve:resource",
                      "audit:read");
              case "technician" ->
                  List.of("resources:read", "workorders:read", "workorders:repair");
              default ->
                  List.of(
                      "resources:read",
                      "reservations:create",
                      "reservations:read",
                      "reservations:cancel",
                      "reservations:checkin",
                      "roomapplications:create",
                      "roomapplications:read",
                      "roomapplications:withdraw",
                      "workorders:create");
            })
          db.update(
              "INSERT IGNORE INTO permissions(user_id,permission) VALUES(?,?)", id, permission);
      }
    };
  }

  public record Login(
      @NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 100) String password) {}

  public record Token(@NotBlank @Size(max = 200) String token) {}

  @PostMapping("/api/v1/auth/login")
  @Transactional
  public Object login(@Valid @RequestBody Login input) {
    var rows =
        db.queryForList("SELECT * FROM users WHERE username=? AND enabled=TRUE", input.username());
    String hash = rows.isEmpty() ? dummy : (String) rows.getFirst().get("password_hash");
    boolean valid = encoder.matches(input.password(), hash);
    if (rows.isEmpty() || !valid) throw Api.fail(401, "INVALID_CREDENTIALS", "用户名或密码不正确");
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    Instant expiry = Instant.now().plus(Duration.ofHours(8));
    String id = (String) rows.getFirst().get("id");
    db.update(
        "INSERT INTO sessions(token_hash,user_id,expires_at) VALUES(?,?,?)",
        Ids.hash(token),
        id,
        Timestamp.from(expiry));
    return Api.ok(Map.of("token", token, "expiresAt", expiry.toString(), "user", actor(id)));
  }

  @GetMapping("/api/v1/auth/me")
  public Object me(@RequestHeader(value = "Authorization", required = false) String auth) {
    return Api.ok(validate(bearer(auth)));
  }

  @PostMapping("/api/v1/auth/logout")
  public Object logout(@RequestHeader(value = "Authorization", required = false) String auth) {
    db.update("DELETE FROM sessions WHERE token_hash=?", Ids.hash(bearer(auth)));
    return Api.ok(Map.of("loggedOut", true));
  }

  @PostMapping("/internal/v1/auth/introspect")
  public Identity.Actor introspect(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @Valid @RequestBody Token input) {
    identity.internal(key);
    return validate(input.token());
  }

  @GetMapping("/api/v1/directory/maintenance-workers")
  public Object maintenanceWorkers(@RequestHeader(value = "Authorization", required = false) String auth) {
    var actor = validate(bearer(auth));
    if (!actor.permissions().contains("workorders:assign")) throw Api.fail(403,"FORBIDDEN","没有派单权限");
    return Api.ok(db.queryForList("SELECT u.id,u.username,u.display_name AS displayName,u.email FROM users u JOIN permissions p ON p.user_id=u.id WHERE p.permission='workorders:repair' AND u.enabled=TRUE ORDER BY u.display_name"));
  }

  private String bearer(String auth) {
    if (auth == null || !auth.startsWith("Bearer ")) throw Api.fail(401, "UNAUTHENTICATED", "请先登录");
    return auth.substring(7);
  }

  private Identity.Actor validate(String token) {
    var ids =
        db.queryForList(
            "SELECT u.id FROM users u JOIN sessions s ON s.user_id=u.id WHERE s.token_hash=? AND"
                + " s.expires_at>CURRENT_TIMESTAMP AND u.enabled=TRUE",
            String.class,
            Ids.hash(token));
    if (ids.isEmpty()) throw Api.fail(401, "UNAUTHENTICATED", "登录已失效");
    return actor(ids.getFirst());
  }

  private Identity.Actor actor(String id) {
    var u = db.queryForMap("SELECT * FROM users WHERE id=?", id);
    return new Identity.Actor(
        id,
        (String) u.get("username"),
        (String) u.get("display_name"),
        (String) u.get("email"),
        (String) u.get("department_code"),
        db.queryForList("SELECT permission FROM permissions WHERE user_id=?", String.class, id));
  }
}
