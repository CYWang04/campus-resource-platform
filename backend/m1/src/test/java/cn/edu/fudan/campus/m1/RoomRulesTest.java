package cn.edu.fudan.campus.m1;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.fudan.campus.common.*;
import java.sql.Time;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RoomRulesTest {
  private Map<String,Object> room(){
    var r=new HashMap<String,Object>();r.put("active",true);r.put("open_time",Time.valueOf("07:00:00"));r.put("close_time",Time.valueOf("23:00:00"));return r;
  }
  private Identity.Actor actor(String department,List<String> permissions){return new Identity.Actor("student","student","Student","student@campus.test",department,permissions);}

  @Test void departmentAccessIsEnforcedOnReadAsWellAsWrite(){
    var r=room();r.put("department_code","MATH");
    assertEquals("DEPARTMENT_FORBIDDEN",assertThrows(Api.Failure.class,()->RoomRules.access(r,actor("CS",List.of("resources:read")))).code);
    assertDoesNotThrow(()->RoomRules.access(r,actor("MATH",List.of("resources:read"))));
    assertDoesNotThrow(()->RoomRules.access(r,actor("CS",List.of("resources:manage"))));
    r.put("department_code",null);assertDoesNotThrow(()->RoomRules.access(r,actor("CS",List.of())));
  }

  @Test void intervalMayEndAtClosingButCurrentTimeAtClosingIsUnavailable(){
    var r=room();var close=Instant.parse("2026-10-26T15:00:00Z");
    assertNull(RoomRules.unavailable(r,close.minusSeconds(3600),close));
    assertEquals("OUTSIDE_OPEN_HOURS",RoomRules.unavailable(r,close,close));
    assertEquals("OUTSIDE_OPEN_HOURS",RoomRules.unavailable(r,close.minusSeconds(3600),close.plusSeconds(3600)));
  }

  @Test void usesShanghaiDateAndRejectsInactiveResources(){
    var r=room();
    assertNull(RoomRules.unavailable(r,Instant.parse("2026-10-25T23:00:00Z"),Instant.parse("2026-10-26T00:00:00Z")));
    assertEquals("OUTSIDE_OPEN_HOURS",RoomRules.unavailable(r,Instant.parse("2026-10-26T14:00:00Z"),Instant.parse("2026-10-26T16:00:00Z")));
    r.put("active",false);assertEquals("RESOURCE_INACTIVE",RoomRules.unavailable(r,Instant.parse("2026-10-25T23:00:00Z"),Instant.parse("2026-10-26T00:00:00Z")));
  }

  @Test void emptyDepartmentMeansPublic(){assertNull(RoomRules.department("  "));assertNull(RoomRules.department(null));assertEquals("CS",RoomRules.department(" CS "));}
}
