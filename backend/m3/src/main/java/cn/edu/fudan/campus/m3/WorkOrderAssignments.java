package cn.edu.fudan.campus.m3;

import cn.edu.fudan.campus.common.Identity;
import cn.edu.fudan.campus.common.Ids;
import java.util.Map;
import org.springframework.stereotype.Service;

/** External lookup precedes the local write transaction in Orders. */
@Service
public class WorkOrderAssignments {
  private final Orders orders;
  private final Identity identity;
  private final WorkerDirectory directory;

  public WorkOrderAssignments(Orders orders, Identity identity, WorkerDirectory directory) {
    this.orders = orders; this.identity = identity; this.directory = directory;
  }

  public Map<String,Object> assign(String authorization, String key, String id, Orders.Assignment input) {
    var actor = identity.require(authorization, "workorders:assign");
    Ids.key(key); Ids.uuid(id); Ids.uuid(input.repairerId());
    var replay = orders.assignmentReplay(actor, key, id, input);
    if (replay != null) return replay;
    var worker = directory.find(authorization, input.repairerId());
    return orders.assign(actor, key, id, input, worker);
  }
}
