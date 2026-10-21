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
  private final Reservations reservations;
  private final ReservationWorker reservationWorker;

  public Resources(JdbcTemplate db, Identity identity, ObjectMapper json, Reservations reservations, ReservationWorker reservationWorker) {
    this.db = db;
    this.identity = identity;
    this.json = json;
    this.reservations = reservations;
    this.reservationWorker = reservationWorker;
  }

  @GetMapping("/api/v1/rooms")
  public Object rooms(@RequestHeader(value = "Authorization", required = false) String auth,
      @RequestParam(required=false) String building,@RequestParam(required=false) String powerType) {
    var actor=identity.require(auth, "resources:read");
    StringBuilder sql=new StringBuilder("SELECT DISTINCT r.id,r.name,r.building,r.department_code AS departmentCode,r.open_time AS openTime,r.close_time AS closeTime,r.availability_version AS availabilityVersion FROM rooms r LEFT JOIN seats s ON s.room_id=r.id WHERE r.active=TRUE AND (r.department_code IS NULL OR r.department_code=?)");
    List<Object> args=new ArrayList<>();args.add(actor.departmentCode());
    if(building!=null&&!building.isBlank()){sql.append(" AND r.building=?");args.add(building);}
    if(powerType!=null&&!powerType.isBlank()){sql.append(" AND s.power_type=? AND s.active=TRUE");args.add(powerType);}
    sql.append(" ORDER BY r.name");
    var items =
        db.queryForList(sql.toString(),args.toArray());
    items.forEach(
        r ->
            r.put(
                "available",
                db.queryForObject(
                        "SELECT COUNT(*) FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND start_at<=CURRENT_TIMESTAMP AND (end_at IS NULL OR end_at>CURRENT_TIMESTAMP)",
                        Integer.class,
                        r.get("id"))
                    == 0));
    return Api.ok(Map.of("items", items, "total", items.size(), "page", 1, "pageSize", 100));
  }

  @GetMapping("/api/v1/rooms/{id}/seats")
  public Object seats(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable String id,
      @RequestParam(required = false) Instant startAt,
      @RequestParam(required = false) Instant endAt) {
    identity.require(auth, "resources:read");
    room(id, false);
    if ((startAt == null) != (endAt == null))
      throw Api.fail(400, "TIME_RANGE_REQUIRED", "开始和结束时间必须同时提供");
    if (startAt != null) Reservations.validateWindow(startAt, endAt, false);
    var seats =
        db.queryForList(
            "SELECT id,code,power_type AS powerType FROM seats WHERE room_id=? AND active=TRUE ORDER BY code", id);
    if (startAt != null) {
      boolean roomAvailable =
          Optional.ofNullable(
                  db.queryForObject(
                      "SELECT COUNT(*) FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND start_at<? AND (end_at IS NULL OR end_at>?)",
                      Integer.class,
                      id,
                      Timestamp.from(endAt),
                      Timestamp.from(startAt)))
              .orElse(0)
              == 0;
      for (var seat : seats) {
        int conflicts =
            Optional.ofNullable(
                    db.queryForObject(
                        "SELECT COUNT(*) FROM reservations WHERE seat_id=? AND status IN ('CONFIRMED','CHECKED_IN')"
                            + " AND start_at<? AND end_at>?",
                        Integer.class,
                        seat.get("id"),
                        Timestamp.from(endAt),
                        Timestamp.from(startAt)))
                .orElse(0);
        seat.put("available", roomAvailable && conflicts == 0);
      }
    }
    return Api.ok(seats);
  }

  @GetMapping("/api/v1/rooms/{id}/check-in-code")
  public Object checkinCode(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id){identity.require(auth,"resources:read");room(id,false);var date=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));return Api.ok(Map.of("roomId",id,"date",date.toString(),"code",reservations.checkinCode(id,date)));}

  public record RoomInput(@NotBlank @Size(max=100)String name,@NotBlank @Size(max=100)String building,@Size(max=40)String departmentCode,@NotNull java.time.LocalTime openTime,@NotNull java.time.LocalTime closeTime,boolean active){}
  public record SeatInput(@NotBlank String roomId,@NotBlank @Size(max=32)String code,@Pattern(regexp="FIXED|RAIL|NONE")String powerType,boolean active){}
  @PostMapping("/api/v1/admin/rooms") @Transactional public Object createRoom(@RequestHeader(value="Authorization",required=false)String auth,@Valid @RequestBody RoomInput input){identity.require(auth,"resources:manage");if(!input.closeTime().isAfter(input.openTime()))throw Api.fail(400,"INVALID_OPEN_HOURS","关闭时间必须晚于开放时间");String id=Ids.uuid();db.update("INSERT INTO rooms(id,name,building,department_code,open_time,close_time,active) VALUES(?,?,?,?,?,?,?)",id,input.name(),input.building(),input.departmentCode(),input.openTime(),input.closeTime(),input.active());return Api.ok(room(id,false));}
  @PutMapping("/api/v1/admin/rooms/{id}") @Transactional public Object updateRoom(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@Valid @RequestBody RoomInput input){identity.require(auth,"resources:manage");room(id,true);if(!input.closeTime().isAfter(input.openTime()))throw Api.fail(400,"INVALID_OPEN_HOURS","关闭时间必须晚于开放时间");if(!input.active()){int active=Optional.ofNullable(db.queryForObject("SELECT COUNT(*) FROM reservations WHERE room_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND end_at>CURRENT_TIMESTAMP",Integer.class,id)).orElse(0);if(active>0)throw Api.fail(409,"ACTIVE_RESERVATIONS","存在有效预约，不能停用教室");}db.update("UPDATE rooms SET name=?,building=?,department_code=?,open_time=?,close_time=?,active=?,availability_version=availability_version+1 WHERE id=?",input.name(),input.building(),input.departmentCode(),input.openTime(),input.closeTime(),input.active(),id);return Api.ok(room(id,false));}
  @PostMapping("/api/v1/admin/seats") @Transactional public Object createSeat(@RequestHeader(value="Authorization",required=false)String auth,@Valid @RequestBody SeatInput input){identity.require(auth,"resources:manage");room(input.roomId(),true);String id=Ids.uuid();db.update("INSERT INTO seats(id,room_id,code,power_type,active) VALUES(?,?,?,?,?)",id,input.roomId(),input.code(),input.powerType(),input.active());return Api.ok(db.queryForMap("SELECT id,room_id AS roomId,code,power_type AS powerType,active FROM seats WHERE id=?",id));}
  @PutMapping("/api/v1/admin/seats/{id}") @Transactional public Object updateSeat(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@Valid @RequestBody SeatInput input){identity.require(auth,"resources:manage");Ids.uuid(id);var rows=db.queryForList("SELECT * FROM seats WHERE id=? FOR UPDATE",id);if(rows.isEmpty())throw Api.fail(404,"SEAT_NOT_FOUND","座位不存在");if(!input.active()){int active=Optional.ofNullable(db.queryForObject("SELECT COUNT(*) FROM reservations WHERE seat_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND end_at>CURRENT_TIMESTAMP",Integer.class,id)).orElse(0);if(active>0)throw Api.fail(409,"ACTIVE_RESERVATIONS","存在有效预约，不能停用座位");}room(input.roomId(),true);db.update("UPDATE seats SET room_id=?,code=?,power_type=?,active=? WHERE id=?",input.roomId(),input.code(),input.powerType(),input.active(),id);return Api.ok(db.queryForMap("SELECT id,room_id AS roomId,code,power_type AS powerType,active FROM seats WHERE id=?",id));}

  @GetMapping("/api/v1/rooms/{id}/availability")
  public Object availability(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable String id) {
    identity.require(auth, "resources:read");
    var room = room(id, false);
    var effects =
        db.queryForList(
            "SELECT source_type AS sourceType,source_id AS sourceId,reason,start_at AS startAt,end_at AS endAt FROM resource_effects"
                + " WHERE room_id=? AND state='ACTIVE' AND start_at<=CURRENT_TIMESTAMP AND (end_at IS NULL OR end_at>CURRENT_TIMESTAMP)",
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
      Instant startAt,
      Instant endAt,
      Long expectedAvailabilityVersion,
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
    if (!Set.of("M3_MAINTENANCE", "M2_ROOM_APPLICATION").contains(sourceType)) throw Api.fail(403, "SOURCE_FORBIDDEN", "来源类型不允许");
    Ids.uuid(sourceId);
    Ids.uuid(e.roomId());
    Ids.uuid(e.operationId());
    var lockedRoom = room(e.roomId(), true);
    if (sourceType.equals("M2_ROOM_APPLICATION") && e.desiredState().equals("ACTIVE")) {
      if (e.startAt() == null || e.endAt() == null || !e.endAt().isAfter(e.startAt()))
        throw Api.fail(400, "TIME_RANGE_REQUIRED", "整教室占用必须提供有效时段");
      if (e.expectedAvailabilityVersion() == null || e.expectedAvailabilityVersion().longValue() != ((Number) lockedRoom.get("availability_version")).longValue())
        throw Api.fail(409, "RESOURCE_VERSION_CONFLICT", "教室资源版本已变化，请重新提交申请");
      int reservations = Optional.ofNullable(db.queryForObject("SELECT COUNT(*) FROM reservations WHERE room_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND start_at<? AND end_at>?", Integer.class, e.roomId(), Timestamp.from(e.endAt()), Timestamp.from(e.startAt()))).orElse(0);
      if (reservations > 0) throw Api.fail(409, "PERSONAL_RESERVATION_CONFLICT", "该时段已有个人座位预约，不能批准整教室申请");
      int effects = Optional.ofNullable(db.queryForObject("SELECT COUNT(*) FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND NOT(source_type=? AND source_id=?) AND start_at<? AND (end_at IS NULL OR end_at>?)", Integer.class, e.roomId(), sourceType, sourceId, Timestamp.from(e.endAt()), Timestamp.from(e.startAt()))).orElse(0);
      if (effects > 0) throw Api.fail(409, "ROOM_EFFECT_CONFLICT", "该时段已有整教室占用或维修限制");
    }
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
            + " resource_effects(source_type,source_id,source_version,room_id,state,start_at,end_at,reason)"
            + " VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE"
            + " source_version=VALUES(source_version),state=VALUES(state),start_at=VALUES(start_at),end_at=VALUES(end_at),reason=VALUES(reason)",
        sourceType,
        sourceId,
        e.sourceVersion(),
        e.roomId(),
        e.desiredState(),
        Timestamp.from(e.startAt() == null ? Instant.now() : e.startAt()),
        e.endAt() == null ? null : Timestamp.from(e.endAt()),
        e.reason());
    if(sourceType.equals("M3_MAINTENANCE")&&e.desiredState().equals("ACTIVE")){
      for(var reservation:db.queryForList("SELECT * FROM reservations WHERE room_id=? AND status='CONFIRMED' AND end_at>CURRENT_TIMESTAMP FOR UPDATE",e.roomId())){
        if(db.update("UPDATE reservations SET status='CANCELLED_MAINTENANCE',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status='CONFIRMED'",reservation.get("id"))==1)reservationWorker.queue(reservation,"MAINTENANCE_CANCELLED","预约因教室紧急维修而取消，座位已释放且不记录违约");
      }
    }
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
