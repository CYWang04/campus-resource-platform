package cn.edu.fudan.campus.m2;

import cn.edu.fudan.campus.common.Api;
import cn.edu.fudan.campus.common.Identity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class MaintenanceCancellationController {
  private final Identity identity;
  private final RoomApplications applications;

  public MaintenanceCancellationController(Identity identity, RoomApplications applications) {
    this.identity = identity;
    this.applications = applications;
  }

  @PostMapping({
    "/internal/v1/room-applications/{id}/maintenance-cancellations",
    "/internal/v1/room-applications/{id}/maintenance-cancellation"
  })
  public Object cancel(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @PathVariable String id,
      @Valid @RequestBody RoomApplications.MaintenanceCancellation event) {
    identity.internal(key);
    return Api.ok(applications.cancelForMaintenance(id, event));
  }
}
