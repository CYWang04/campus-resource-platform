package cn.edu.fudan.campus.m1;

import cn.edu.fudan.campus.common.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class Resources {
  private final JdbcTemplate db;
  private final Identity identity;
  private final ObjectMapper json;

  public Resources(JdbcTemplate db, Identity identity, ObjectMapper json) {
    this.db = db;
    this.identity = identity;
    this.json = json;
  }

  @GetMapping("/api/v1/rooms")
  public Object rooms(@RequestHeader(value = "Authorization", required = false) String auth) {
    identity.require(auth, "resources:read");
    var items =
        db.queryForList(
            "SELECT id,name,building,availability_version AS availabilityVersion FROM rooms ORDER"
                + " BY name");
    items.forEach(
        r ->
            r.put(
                "available",
                db.queryForObject(
                        "SELECT COUNT(*) FROM resource_effects WHERE room_id=? AND state='ACTIVE'",
                        Integer.class,
                        r.get("id"))
                    == 0));
    return Api.ok(Map.of("items", items, "total", items.size(), "page", 1, "pageSize", 100));
  }

  @GetMapping("/api/v1/rooms/{id}/seats")
  public Object seats(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable String id) {
    identity.require(auth, "resources:read");
    room(id, false);
    return Api.ok(
        db.queryForList(
            "SELECT id,code,power_type AS powerType FROM seats WHERE room_id=? ORDER BY code", id));
  }

  @GetMapping("/api/v1/rooms/{id}/availability")
  public Object availability(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable String id) {
    identity.require(auth, "resources:read");
    var room = room(id, false);
    var effects =
        db.queryForList(
            "SELECT source_type AS sourceType,source_id AS sourceId,reason FROM resource_effects"
                + " WHERE room_id=? AND state='ACTIVE'",
            id);
    return Api.ok(
        Map.of(
            "roomId",
            id,
            "availabilityVersion",
            room.get("availability_version"),
            "available",
            effects.isEmpty(),
            "effects",
            effects));
  }

  public record Effect(
      @NotBlank String operationId,
      @Min(1) long sourceVersion,
      @NotBlank String roomId,
      @Pattern(regexp = "ACTIVE|RELEASED") @NotNull String desiredState,
      @NotBlank @Size(max = 500) String reason) {}

  @PutMapping("/internal/v1/resource-effects/{sourceType}/{sourceId}")
  @Transactional
  public Object effect(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @PathVariable String sourceType,
      @PathVariable String sourceId,
      @Valid @RequestBody Effect e)
      throws Exception {
    identity.internal(key);
    if (!sourceType.equals("M3_MAINTENANCE")) throw Api.fail(403, "SOURCE_FORBIDDEN", "当前仅开放维修来源");
    Ids.uuid(sourceId);
    Ids.uuid(e.roomId());
    Ids.uuid(e.operationId());
    room(e.roomId(), true);
    String fp = Ids.hash(sourceType + sourceId + json.writeValueAsString(e));
    var previous = db.queryForList("SELECT * FROM operations WHERE id=?", e.operationId());
    if (!previous.isEmpty()) {
      var old = previous.getFirst();
      if (!fp.equals(old.get("fingerprint")))
        throw Api.fail(409, "IDEMPOTENCY_CONFLICT", "操作编号已经用于其他内容");
      Map<String, Object> result = json.readValue((String) old.get("result_json"), Map.class);
      var current =
          db.queryForMap(
              "SELECT state FROM resource_effects WHERE source_type=? AND source_id=?",
              sourceType,
              sourceId);
      result.put("currentSourceState", current.get("state"));
      return Api.ok(result);
    }
    var sources =
        db.queryForList(
            "SELECT * FROM resource_effects WHERE source_type=? AND source_id=? FOR UPDATE",
            sourceType,
            sourceId);
    if (!sources.isEmpty()) {
      var old = sources.getFirst();
      if (!e.roomId().equals(old.get("room_id")))
        throw Api.fail(409, "SOURCE_ROOM_CONFLICT", "来源不能更换教室");
      if (e.sourceVersion() <= ((Number) old.get("source_version")).longValue())
        throw Api.fail(409, "STALE_SOURCE_VERSION", "来源版本过期");
    }
    db.update(
        "INSERT INTO"
            + " resource_effects(source_type,source_id,source_version,room_id,state,start_at,reason)"
            + " VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " source_version=VALUES(source_version),state=VALUES(state),reason=VALUES(reason)",
        sourceType,
        sourceId,
        e.sourceVersion(),
        e.roomId(),
        e.desiredState(),
        Timestamp.from(Instant.now()),
        e.reason());
    db.update(
        "UPDATE rooms SET availability_version=availability_version+1 WHERE id=?", e.roomId());
    var result =
        Map.of(
            "operationId",
            e.operationId(),
            "status",
            "APPLIED",
            "sourceState",
            e.desiredState(),
            "currentSourceState",
            e.desiredState(),
            "roomId",
            e.roomId());
    db.update(
        "INSERT INTO operations(id,fingerprint,result_json) VALUES(?,?,?)",
        e.operationId(),
        fp,
        json.writeValueAsString(result));
    return Api.ok(result);
  }

  private Map<String, Object> room(String id, boolean lock) {
    Ids.uuid(id);
    var rows = db.queryForList("SELECT * FROM rooms WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw Api.fail(404, "ROOM_NOT_FOUND", "教室不存在");
    return rows.getFirst();
  }
}
