package cn.edu.fudan.campus.m1;

import cn.edu.fudan.campus.common.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
public class Reservations {
  private static final Duration MAX_DURATION = Duration.ofHours(4);
  private static final Duration MAX_ADVANCE = Duration.ofDays(7);
  private final JdbcTemplate db;
  private final Identity identity;
  private final ObjectMapper json;

  public Reservations(JdbcTemplate db, Identity identity, ObjectMapper json) {
    this.db = db;
    this.identity = identity;
    this.json = json;
  }

  public record Create(
      @NotBlank String seatId,
      @NotNull Instant startAt,
      @NotNull Instant endAt,
      @NotBlank @Size(max = 200) String purpose) {}
  public record Repeat(@NotNull Instant startAt,@NotNull Instant endAt) {}
  public record CheckIn(@NotBlank @Pattern(regexp="\\d{6}") String code) {}

  @PostMapping("/api/v1/reservations")
  @Transactional
  public Object create(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Create input)
      throws Exception {
    var actor = identity.require(auth, "reservations:create");
    String idempotencyKey = key(key);
    Ids.uuid(input.seatId());
    validateWindow(input.startAt(), input.endAt(), true);
    String fingerprint = Ids.hash("CREATE" + json.writeValueAsString(input));
    var replay = replay(actor.id(), idempotencyKey, fingerprint);
    if (replay != null) return Api.ok(replay);

    var seats =
        db.queryForList(
            "SELECT s.id,s.code,s.room_id,r.name AS room_name,r.department_code,r.open_time,r.close_time,r.active AS room_active,s.active AS seat_active FROM seats s JOIN rooms r ON"
                + " r.id=s.room_id WHERE s.id=? FOR UPDATE",
            input.seatId());
    if (seats.isEmpty()) throw Api.fail(404, "SEAT_NOT_FOUND", "座位不存在");
    var seat = seats.getFirst();
    if(!Boolean.TRUE.equals(seat.get("room_active"))||!Boolean.TRUE.equals(seat.get("seat_active")))throw Api.fail(409,"RESOURCE_INACTIVE","教室或座位已停用");
    if(seat.get("department_code")!=null&&!Objects.equals(seat.get("department_code"),actor.departmentCode()))throw Api.fail(403,"DEPARTMENT_FORBIDDEN","该自习室仅向所属院系开放");
    var zone=ZoneId.of("Asia/Shanghai");var localStart=LocalDateTime.ofInstant(input.startAt(),zone);var localEnd=LocalDateTime.ofInstant(input.endAt(),zone);
    if(!localStart.toLocalDate().equals(localEnd.toLocalDate())||localStart.toLocalTime().isBefore(((java.sql.Time)seat.get("open_time")).toLocalTime())||localEnd.toLocalTime().isAfter(((java.sql.Time)seat.get("close_time")).toLocalTime()))throw Api.fail(409,"OUTSIDE_OPEN_HOURS","预约时段不在教室开放时间内");
    String roomId = (String) seat.get("room_id");
    Integer effects =
        db.queryForObject(
            "SELECT COUNT(*) FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND start_at<? AND (end_at IS NULL OR end_at>?)",
            Integer.class,
            roomId,
            Timestamp.from(input.endAt()),
            Timestamp.from(input.startAt()));
    if (effects != null && effects > 0)
      throw Api.fail(409, "ROOM_UNAVAILABLE", "教室当前受维修限制，不能预约");
    Integer conflicts =
        db.queryForObject(
            "SELECT COUNT(*) FROM reservations WHERE seat_id=? AND status IN ('CONFIRMED','CHECKED_IN') AND"
                + " start_at<? AND end_at>?",
            Integer.class,
            input.seatId(),
            Timestamp.from(input.endAt()),
            Timestamp.from(input.startAt()));
    if (conflicts != null && conflicts > 0)
      throw Api.fail(409, "RESERVATION_CONFLICT", "该座位在所选时段已被预约");

    String id = UUID.randomUUID().toString();
    db.update(
        "INSERT INTO reservations(id,user_id,user_email,seat_id,room_id,start_at,end_at,status,purpose)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        actor.id(),
        actor.email(),
        input.seatId(),
        roomId,
        Timestamp.from(input.startAt()),
        Timestamp.from(input.endAt()),
        "CONFIRMED",
        input.purpose().trim());
    var result = view(id, actor.id(), true);
    save(actor.id(), idempotencyKey, fingerprint, result);
    return Api.ok(result);
  }

  @PostMapping("/api/v1/reservations/{id}/repeat")
  @Transactional
  public Object repeat(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody Repeat input) throws Exception {
    var actor=identity.require(auth,"reservations:create");Ids.uuid(id);
    var rows=db.queryForList("SELECT seat_id,purpose FROM reservations WHERE id=? AND user_id=?",id,actor.id());
    if(rows.isEmpty())throw Api.fail(404,"RESERVATION_NOT_FOUND","预约不存在");
    var old=rows.getFirst();return create(auth,key,new Create((String)old.get("seat_id"),input.startAt(),input.endAt(),(String)old.get("purpose")));
  }

  @PostMapping("/api/v1/reservations/{id}/check-in")
  @Transactional
  public Object checkIn(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody CheckIn input) throws Exception {
    var actor=identity.require(auth,"reservations:checkin");Ids.uuid(id);String k=key(key),fp=Ids.hash("CHECKIN"+id+input.code());var replay=replay(actor.id(),k,fp);if(replay!=null)return Api.ok(replay);
    var rows=db.queryForList("SELECT * FROM reservations WHERE id=? AND user_id=? FOR UPDATE",id,actor.id());if(rows.isEmpty())throw Api.fail(404,"RESERVATION_NOT_FOUND","预约不存在");var r=rows.getFirst();
    if(r.get("status").equals("CHECKED_IN")){var result=view(id,actor.id(),true);save(actor.id(),k,fp,result);return Api.ok(result);}
    if(!r.get("status").equals("CONFIRMED"))throw Api.fail(409,"INVALID_STATE","当前预约不能签到");
    Instant start=((Timestamp)r.get("start_at")).toInstant(),now=Instant.now();if(now.isBefore(start)||now.isAfter(start.plus(Duration.ofMinutes(15))))throw Api.fail(409,"CHECKIN_WINDOW_CLOSED","仅可在开始后15分钟内签到");
    LocalDate date=LocalDate.ofInstant(start,ZoneId.of("Asia/Shanghai"));String expected=checkinCode((String)r.get("room_id"),date);if(!expected.equals(input.code()))throw Api.fail(409,"INVALID_CHECKIN_CODE","签到码不正确");
    db.update("UPDATE reservations SET status='CHECKED_IN',checked_in_at=CURRENT_TIMESTAMP(6),checkin_code_date=? WHERE id=?",java.sql.Date.valueOf(date),id);var result=view(id,actor.id(),true);save(actor.id(),k,fp,result);return Api.ok(result);
  }

  @GetMapping("/api/v1/reservations/violations") public Object violations(@RequestHeader(value="Authorization",required=false)String auth){var a=identity.require(auth,"reservations:read");return Api.ok(db.queryForList("SELECT id,reservation_id AS reservationId,reason,created_at AS createdAt FROM violation_records WHERE user_id=? ORDER BY created_at DESC",a.id()));}
  @GetMapping("/api/v1/admin/reservation-statistics") public Object statistics(@RequestHeader(value="Authorization",required=false)String auth){identity.require(auth,"reservations:stats");Map<String,Object> out=new LinkedHashMap<>();out.put("byStatus",db.queryForList("SELECT status,COUNT(*) AS count FROM reservations GROUP BY status ORDER BY status"));out.put("byRoom",db.queryForList("SELECT rm.name AS roomName,COUNT(*) AS count FROM reservations r JOIN rooms rm ON rm.id=r.room_id GROUP BY rm.id,rm.name ORDER BY count DESC"));out.put("byStartHour",db.queryForList("SELECT HOUR(start_at) AS hour,COUNT(*) AS count FROM reservations GROUP BY HOUR(start_at) ORDER BY hour"));out.put("violations",db.queryForObject("SELECT COUNT(*) FROM violation_records",Integer.class));return Api.ok(out);}

  String checkinCode(String roomId,LocalDate date){String hash=Ids.hash(identity.key()+"|"+roomId+"|"+date);long value=Long.parseLong(hash.substring(0,12),16)%1000000;return String.format(Locale.ROOT,"%06d",value);}

  @GetMapping("/api/v1/reservations")
  public Object list(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestParam(defaultValue = "1") @Min(1) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize) {
    var actor = identity.require(auth, "reservations:read");
    if (page < 1 || pageSize < 1 || pageSize > 100)
      throw Api.fail(400, "INVALID_PAGE", "分页参数超出范围");
    int total =
        Optional.ofNullable(
                db.queryForObject(
                    "SELECT COUNT(*) FROM reservations WHERE user_id=?", Integer.class, actor.id()))
            .orElse(0);
    var rows =
        db.queryForList(
            "SELECT r.*,s.code AS seat_code,rm.name AS room_name,rm.building FROM reservations r"
                + " JOIN seats s ON s.id=r.seat_id JOIN rooms rm ON rm.id=r.room_id WHERE"
                + " r.user_id=? ORDER BY r.start_at DESC LIMIT ? OFFSET ?",
            actor.id(),
            pageSize,
            (page - 1) * pageSize);
    return Api.ok(
        Map.of(
            "items", rows.stream().map(this::publicView).toList(),
            "total", total,
            "page", page,
            "pageSize", pageSize));
  }

  @GetMapping("/api/v1/reservations/{id}")
  public Object detail(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @PathVariable String id) {
    var actor = identity.require(auth, "reservations:read");
    Ids.uuid(id);
    return Api.ok(view(id, actor.id(), true));
  }

  @PostMapping("/api/v1/reservations/{id}/cancel")
  @Transactional
  public Object cancel(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @PathVariable String id)
      throws Exception {
    var actor = identity.require(auth, "reservations:cancel");
    Ids.uuid(id);
    String idempotencyKey = key(key);
    String fingerprint = Ids.hash("CANCEL" + id);
    var replay = replay(actor.id(), idempotencyKey, fingerprint);
    if (replay != null) return Api.ok(replay);
    var rows =
        db.queryForList(
            "SELECT * FROM reservations WHERE id=? AND user_id=? FOR UPDATE", id, actor.id());
    if (rows.isEmpty()) throw Api.fail(404, "RESERVATION_NOT_FOUND", "预约不存在");
    var reservation = rows.getFirst();
    if (!"CONFIRMED".equals(reservation.get("status")))
      throw Api.fail(409, "RESERVATION_NOT_ACTIVE", "预约已经取消或不能再操作");
    Instant startAt = ((Timestamp) reservation.get("start_at")).toInstant();
    if (!Instant.now().isBefore(startAt))
      throw Api.fail(409, "RESERVATION_STARTED", "预约开始后不能取消");
    db.update(
        "UPDATE reservations SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",
        id);
    var result = view(id, actor.id(), true);
    save(actor.id(), idempotencyKey, fingerprint, result);
    return Api.ok(result);
  }

  static void validateWindow(Instant start, Instant end, boolean enforceAdvance) {
    if (start.getEpochSecond() % 3600 != 0 || end.getEpochSecond() % 3600 != 0)
      throw Api.fail(400, "WHOLE_HOUR_REQUIRED", "预约时间必须为整点");
    Duration duration = Duration.between(start, end);
    if (duration.isNegative() || duration.isZero() || duration.compareTo(MAX_DURATION) > 0)
      throw Api.fail(400, "INVALID_DURATION", "预约时长必须为 1 至 4 小时");
    if (enforceAdvance) {
      Instant now = Instant.now();
      if (!start.isAfter(now)) throw Api.fail(409, "START_IN_PAST", "预约开始时间必须晚于当前时间");
      if (start.isAfter(now.plus(MAX_ADVANCE)))
        throw Api.fail(409, "START_TOO_LATE", "只能预约未来 7 天内的时段");
    }
  }

  private String key(String value) {
    if (value == null || value.isBlank() || value.length() > 100)
      throw Api.fail(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的幂等键");
    return value;
  }

  private Map<String, Object> replay(String actor, String key, String fingerprint)
      throws Exception {
    var rows =
        db.queryForList(
            "SELECT fingerprint,result_json FROM reservation_commands WHERE actor_id=? AND"
                + " idempotency_key=? FOR UPDATE",
            actor,
            key);
    if (rows.isEmpty()) return null;
    if (!fingerprint.equals(rows.getFirst().get("fingerprint")))
      throw Api.fail(409, "IDEMPOTENCY_CONFLICT", "幂等键已经用于其他操作");
    return json.readValue(
        rows.getFirst().get("result_json").toString(), new TypeReference<Map<String, Object>>() {});
  }

  private void save(String actor, String key, String fingerprint, Map<String, Object> result)
      throws Exception {
    db.update(
        "INSERT INTO reservation_commands(actor_id,idempotency_key,fingerprint,result_json)"
            + " VALUES(?,?,?,?)",
        actor,
        key,
        fingerprint,
        json.writeValueAsString(result));
  }

  private Map<String, Object> view(String id, String actor, boolean fail) {
    var rows =
        db.queryForList(
            "SELECT r.*,s.code AS seat_code,rm.name AS room_name,rm.building FROM reservations r"
                + " JOIN seats s ON s.id=r.seat_id JOIN rooms rm ON rm.id=r.room_id WHERE r.id=?"
                + " AND r.user_id=?",
            id,
            actor);
    if (rows.isEmpty() && fail) throw Api.fail(404, "RESERVATION_NOT_FOUND", "预约不存在");
    return rows.isEmpty() ? Map.of() : publicView(rows.getFirst());
  }

  private Map<String, Object> publicView(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String name :
        List.of("id", "seat_id", "room_id", "seat_code", "room_name", "building", "status", "purpose"))
      result.put(name, row.get(name));
    result.put("start_at", ((Timestamp) row.get("start_at")).toInstant().toString());
    result.put("end_at", ((Timestamp) row.get("end_at")).toInstant().toString());
    result.put("created_at", ((Timestamp) row.get("created_at")).toInstant().toString());
    Object cancelled = row.get("cancelled_at");
    result.put("cancelled_at", cancelled == null ? null : ((Timestamp) cancelled).toInstant().toString());
    Object checked = row.get("checked_in_at");
    result.put("checked_in_at", checked == null ? null : ((Timestamp) checked).toInstant().toString());
    return result;
  }
}
