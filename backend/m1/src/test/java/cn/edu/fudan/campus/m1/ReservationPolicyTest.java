package cn.edu.fudan.campus.m1;

import static org.junit.jupiter.api.Assertions.*;
import cn.edu.fudan.campus.common.Api;
import java.time.*;
import org.junit.jupiter.api.Test;

class ReservationPolicyTest {
  private final Instant now=Instant.parse("2026-10-26T00:00:00Z");
  private final Instant start=now.plusSeconds(3600);
  private final ReservationPolicy.Settings defaults=new ReservationPolicy.Settings(4,7,1);

  @Test void durationUsesConfiguredLimit(){
    var error=assertThrows(Api.Failure.class,()->ReservationPolicy.validate(start,start.plusSeconds(6*3600),true,defaults,now));
    assertEquals("INVALID_DURATION",error.code);
    assertDoesNotThrow(()->ReservationPolicy.validate(start,start.plusSeconds(6*3600),true,new ReservationPolicy.Settings(6,7,2),now));
  }

  @Test void rejectsEmptyReverseAndFractionalHours(){
    assertThrows(Api.Failure.class,()->ReservationPolicy.validate(start,start,true,defaults,now));
    assertThrows(Api.Failure.class,()->ReservationPolicy.validate(start,start.minusSeconds(3600),true,defaults,now));
    assertEquals("WHOLE_HOUR_REQUIRED",assertThrows(Api.Failure.class,()->ReservationPolicy.validate(start.plusNanos(1),start.plusSeconds(3600),true,defaults,now)).code);
    assertThrows(Api.Failure.class,()->ReservationPolicy.validate(start.plusSeconds(60),start.plusSeconds(3660),true,defaults,now));
  }

  @Test void advanceWindowIsBoundedAndConfigurable(){
    assertEquals("START_IN_PAST",assertThrows(Api.Failure.class,()->ReservationPolicy.validate(now,now.plusSeconds(3600),true,defaults,now)).code);
    Instant sevenDays=now.plus(Duration.ofDays(7));
    assertDoesNotThrow(()->ReservationPolicy.validate(sevenDays,sevenDays.plusSeconds(3600),true,defaults,now));
    assertEquals("START_TOO_LATE",assertThrows(Api.Failure.class,()->ReservationPolicy.validate(sevenDays.plusSeconds(3600),sevenDays.plusSeconds(7200),true,defaults,now)).code);
    assertThrows(Api.Failure.class,()->ReservationPolicy.validate(start,start.plusSeconds(3600),true,new ReservationPolicy.Settings(4,1,1),now.minus(Duration.ofDays(2))));
  }

  @Test void checkInWindowIsHalfOpen(){
    assertFalse(ReservationPolicy.checkInOpen(start.minusNanos(1),start));
    assertTrue(ReservationPolicy.checkInOpen(start,start));
    assertTrue(ReservationPolicy.checkInOpen(start.plusSeconds(900).minusNanos(1),start));
    assertFalse(ReservationPolicy.checkInOpen(start.plusSeconds(900),start));
    assertFalse(ReservationPolicy.checkInOpen(start.plusSeconds(901),start));
  }
}
