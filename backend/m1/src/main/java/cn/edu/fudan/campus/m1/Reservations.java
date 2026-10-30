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
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class Reservations {
  private final JdbcTemplate db;
  private final Identity identity;
  private final ObjectMapper json;
  private final ReservationPolicy policy;
  private final ResourceAudit audit;

  public Reservations(JdbcTemplate db, Identity identity, ObjectMapper json, ReservationPolicy policy, ResourceAudit audit) {
    this.db = db;
    this.identity = identity;
    this.json = json;
    this.policy = policy;
    this.audit = audit;
  }

  public record Create(
      @NotBlank String seatId,
      @NotNull Instant startAt,
      @NotNull Instant endAt,
      @NotBlank @Size(max = 200) String purpose) {}
  public record Repeat(@NotNull Instant startAt,@NotNull Instant endAt) {}
  public record CheckIn(@NotBlank @Pattern(regexp="\\d{6}") String code) {}

  @PostMapping("/api/v1/reservations")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object create(
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Create input)
      throws Exception {
    var actor = identity.require(auth, "reservations:create");
    String idempotencyKey = key(key);
    Ids.uuid(input.seatId());
    String fingerprint = Ids.hash("CREATE" + json.writeValueAsString(input));
    var replay = replay(actor.id(), idempotencyKey, fingerprint);
    if (replay != null) return Api.ok(replay);
    policy.validate(input.startAt(), input.endAt(), true);

    var locations=db.queryForList("SELECT room_id FROM seats WHERE id=?",input.seatId());
    if(locations.isEmpty())throw Api.fail(404,"SEAT_NOT_FOUND","座位不存在");
    String roomId=(String)locations.getFirst().get("room_id");
    var room=db.queryForMap("SELECT * FROM rooms WHERE id=? FOR UPDATE",roomId);
    var seat=db.queryForMap("SELECT * FROM seats WHERE id=? FOR UPDATE",input.seatId());
    if(!Boolean.TRUE.equals(seat.get("active")))throw Api.fail(409,"RESOURCE_INACTIVE","座位已停用");
    RoomRules.access(room,actor);
    RoomRules.requireOpen(room,input.startAt(),input.endAt());
    Integer effects =
        db.queryForObject(
            "SELECT COUNT(*) FROM resource_effects WHERE room_id=? AND state='ACTIVE' AND start_at<? AND (end_at IS NULL OR end_at>?)",
            Integer.class,
            roomId,
            Timestamp.from(input.endAt()),
            Timestamp.from(input.startAt()));
    if (effects != null && effects > 0)
      throw Api.fail(409, "ROOM_UNAVAILABLE", "教室在该时段受整室占用或维修限制，不能预约");
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
    audit.record(actor.id(),"RESERVATION_CREATED","M1_RESERVATION",id,"创建个人预约");
    return Api.ok(result);
  }

  @PostMapping("/api/v1/reservations/{id}/repeat")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object repeat(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody Repeat input) throws Exception {
    var actor=identity.require(auth,"reservations:create");Ids.uuid(id);
    var rows=db.queryForList("SELECT seat_id,purpose FROM reservations WHERE id=? AND user_id=?",id,actor.id());
    if(rows.isEmpty())throw Api.fail(404,"RESERVATION_NOT_FOUND","预约不存在");
    var old=rows.getFirst();return create(auth,key,new Create((String)old.get("seat_id"),input.startAt(),input.endAt(),(String)old.get("purpose")));
  }

  @PostMapping("/api/v1/reservations/{id}/check-in")
  @Transactional(isolation=Isolation.READ_COMMITTED)
  public Object checkIn(@RequestHeader(value="Authorization",required=false)String auth,@RequestHeader(value="Idempotency-Key",required=false)String key,@PathVariable String id,@Valid @RequestBody CheckIn input) throws Exception {
    var actor=identity.require(auth,"reservations:checkin");Ids.uuid(id);String k=key(key),fp=Ids.hash("CHECKIN"+id+input.code());var replay=replay(actor.id(),k,fp);if(replay!=null)return Api.ok(replay);
    var rows=db.queryForList("SELECT * FROM reservations WHERE id=? AND user_id=? FOR UPDATE",id,actor.id());if(rows.isEmpty())throw Api.fail(404,"RESERVATION_NOT_FOUND","预约不存在");var r=rows.getFirst();
    if(r.get("status").equals("CHECKED_IN")){var result=view(id,actor.id(),true);save(actor.id(),k,fp,result);return Api.ok(result);}
    if(!r.get("status").equals("CONFIRMED"))throw Api.fail(409,"INVALID_STATE","当前预约不能签到");
    Instant start=((Timestamp)r.get("start_at")).toInstant(),now=policy.now();if(!ReservationPolicy.checkInOpen(now,start))throw Api.fail(409,"CHECKIN_WINDOW_CLOSED","仅可在开始至开始后15分钟之前签到");
    LocalDate date=LocalDate.ofInstant(start,ZoneId.of("Asia/Shanghai"));String expected=checkinCode((String)r.get("room_id"),date);if(!expected.equals(input.code()))throw Api.fail(409,"INVALID_CHECKIN_CODE","签到码不正确");
    db.update("UPDATE reservations SET status='CHECKED_IN',checked_in_at=CURRENT_TIMESTAMP(6),checkin_code_date=? WHERE id=?",java.sql.Date.valueOf(date),id);var result=view(id,actor.id(),true);save(actor.id(),k,fp,result);audit.record(actor.id(),"CHECKED_IN","M1_RESERVATION",id,"现场编码签到");return Api.ok(result);
  }

  @GetMapping("/api/v1/reservations/violations") public Object violations(@RequestHeader(value="Authorization",required=false)String auth){var a=identity.require(auth,"reservations:read");return Api.ok(db.queryForList("SELECT id,reservation_id AS reservationId,reason,created_at AS createdAt FROM violation_records WHERE user_id=? ORDER BY created_at DESC",a.id()));}
  @GetMapping("/api/v1/admin/reservation-statistics")
  public Object statistics(@RequestHeader(value="Authorization",required=false)String auth,
      @RequestParam(required=false)LocalDate from,@RequestParam(required=false)LocalDate to,
      @RequestParam(required=false)String roomId){
    identity.require(auth,"reservations:stats");
    if(from!=null&&to!=null&&to.isBefore(from))throw Api.fail(400,"INVALID_DATE_RANGE","结束日期不能早于开始日期");
    StringBuilder where=new StringBuilder(" WHERE 1=1");List<Object> args=new ArrayList<>();
    if(from!=null){where.append(" AND r.start_at>=?");args.add(Timestamp.from(from.atStartOfDay(RoomRules.ZONE).toInstant()));}
    if(to!=null){where.append(" AND r.start_at<?");args.add(Timestamp.from(to.plusDays(1).atStartOfDay(RoomRules.ZONE).toInstant()));}
    if(roomId!=null&&!roomId.isBlank()){Ids.uuid(roomId);where.append(" AND r.room_id=?");args.add(roomId);}
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("byStatus",db.queryForList("SELECT CASE WHEN r.status='CHECKED_IN' AND r.end_at<=CURRENT_TIMESTAMP THEN 'COMPLETED' ELSE r.status END AS status,COUNT(*) AS count FROM reservations r"+where+" GROUP BY CASE WHEN r.status='CHECKED_IN' AND r.end_at<=CURRENT_TIMESTAMP THEN 'COMPLETED' ELSE r.status END ORDER BY status",args.toArray()));
    out.put("byRoom",db.queryForList("SELECT rm.name AS roomName,COUNT(*) AS count,SUM(CASE WHEN r.status IN ('CONFIRMED','CHECKED_IN') THEN TIMESTAMPDIFF(SECOND,r.start_at,r.end_at)/3600.0 ELSE 0 END) AS reservationHours FROM reservations r JOIN rooms rm ON rm.id=r.room_id"+where+" GROUP BY rm.id,rm.name ORDER BY count DESC",args.toArray()));
    out.put("byStartHour",db.queryForList("SELECT HOUR(DATE_ADD(r.start_at,INTERVAL 8 HOUR)) AS hour,COUNT(*) AS count FROM reservations r"+where+" GROUP BY HOUR(DATE_ADD(r.start_at,INTERVAL 8 HOUR)) ORDER BY hour",args.toArray()));
    out.put("violations",db.queryForObject("SELECT COUNT(*) FROM violation_records v JOIN reservations r ON r.id=v.reservation_id"+where,Integer.class,args.toArray()));
    return Api.ok(out);
  }

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
                + " r.user_id=? ORDER BY r.start_at DESC,r.id LIMIT ? OFFSET ?",
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
  @Transactional(isolation=Isolation.READ_COMMITTED)
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
    if (!policy.now().isBefore(startAt))
      throw Api.fail(409, "RESERVATION_STARTED", "预约开始后不能取消");
    db.update(
        "UPDATE reservations SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP(6) WHERE id=?",
        id);
    var result = view(id, actor.id(), true);
    save(actor.id(), idempotencyKey, fingerprint, result);
    audit.record(actor.id(),"RESERVATION_CANCELLED","M1_RESERVATION",id,"用户主动取消预约");
    return Api.ok(result);
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
    result.put("display_status", "CHECKED_IN".equals(row.get("status")) && !((Timestamp)row.get("end_at")).toInstant().isAfter(policy.now()) ? "COMPLETED" : row.get("status"));
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
