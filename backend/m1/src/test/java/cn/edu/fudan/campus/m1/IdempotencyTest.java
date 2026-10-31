package cn.edu.fudan.campus.m1;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import cn.edu.fudan.campus.common.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class IdempotencyTest {
  private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @Test void successfulCreationCanReplayAfterItsStartTime() throws Exception {
    var db=mock(JdbcTemplate.class);var identity=mock(Identity.class);var policy=mock(ReservationPolicy.class);
    var actor=new Identity.Actor("student","student","Student","student@campus.test","CS",List.of("reservations:create"));
    when(identity.require("Bearer test","reservations:create")).thenReturn(actor);
    var input=new Reservations.Create(UUID.randomUUID().toString(),Instant.parse("2020-01-01T00:00:00Z"),Instant.parse("2020-01-01T01:00:00Z"),"复习");
    String fingerprint=Ids.hash("CREATE"+json.writeValueAsString(input));
    when(db.queryForList("SELECT fingerprint,result_json FROM reservation_commands WHERE actor_id=? AND idempotency_key=? FOR UPDATE","student","same-key"))
        .thenReturn(List.of(Map.of("fingerprint",fingerprint,"result_json","{\"id\":\"existing-reservation\",\"status\":\"CONFIRMED\"}")));
    doThrow(Api.fail(409,"START_IN_PAST","past")).when(policy).validate(input.startAt(),input.endAt(),true);
    var controller=new Reservations(db,identity,json,policy,mock(ResourceAudit.class));
    var response=(Map<?,?>)controller.create("Bearer test","same-key",input);
    assertEquals("existing-reservation",((Map<?,?>)response.get("data")).get("id"));
    verify(policy,never()).validate(any(),any(),anyBoolean());
    assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(i->i.getMethod().getName().equals("update")));
  }

  @Test void effectReplayUsesCurrentStateDespiteAnOlderExpectedRoomVersion() throws Exception {
    var db=mock(JdbcTemplate.class);var identity=mock(Identity.class);
    String roomId=UUID.randomUUID().toString(),source=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
    var effect=new Resources.Effect(operation,1,roomId,"ACTIVE",Instant.parse("2030-01-01T00:00:00Z"),Instant.parse("2030-01-01T01:00:00Z"),2L,"申请");
    when(db.queryForList("SELECT * FROM rooms WHERE id=? FOR UPDATE",roomId)).thenReturn(List.of(Map.of("id",roomId,"availability_version",3L)));
    when(db.queryForList("SELECT * FROM operations WHERE id=?",operation)).thenReturn(List.of(Map.of("fingerprint",Ids.hash("M2_ROOM_APPLICATION"+source+json.writeValueAsString(effect)),"result_json","{\"status\":\"APPLIED\",\"sourceState\":\"ACTIVE\"}")));
    var current=new HashMap<String,Object>();current.put("state","CANCELLED");current.put("invalidated_by",UUID.randomUUID().toString());
    when(db.queryForMap("SELECT state,invalidated_by FROM resource_effects WHERE source_type=? AND source_id=?","M2_ROOM_APPLICATION",source)).thenReturn(current);
    var controller=new Resources(db,identity,json,mock(Reservations.class),mock(ReservationWorker.class),mock(ReservationPolicy.class),mock(ResourceAudit.class));
    var response=(Map<?,?>)controller.effect("service-key","M2_ROOM_APPLICATION",source,effect);
    var data=(Map<?,?>)response.get("data");
    assertEquals("ACTIVE",data.get("sourceState"));assertEquals("CANCELLED",data.get("currentSourceState"));
    assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(i->i.getMethod().getName().equals("update")));
  }
}
