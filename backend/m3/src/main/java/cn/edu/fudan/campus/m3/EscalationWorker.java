package cn.edu.fudan.campus.m3;

import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class EscalationWorker {
  private final WorkOrderEscalations escalations;

  public EscalationWorker(WorkOrderEscalations escalations) { this.escalations = escalations; }

  @Scheduled(fixedDelay = 60000)
  public void run() {
    for (String id : escalations.due()) {
      try { escalations.escalate(id); }
      catch (DataAccessException e) {
        LoggerFactory.getLogger(EscalationWorker.class)
            .warn("Escalation transaction failed for order {}; will retry next scan", id);
      }
    }
  }
}
