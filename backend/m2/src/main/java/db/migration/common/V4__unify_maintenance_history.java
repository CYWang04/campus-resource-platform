package db.migration.common;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Forward-only adaptation for either immutable V3 history; no history repair or data deletion. */
public class V4__unify_maintenance_history extends BaseJavaMigration {
  @Override
  public void migrate(Context context) throws SQLException {
    Connection connection = context.getConnection();
    execute(connection, "CREATE TABLE IF NOT EXISTS application_cancellation_events ("
        + "event_id CHAR(36) PRIMARY KEY, application_id CHAR(36) NOT NULL,"
        + "fingerprint CHAR(64) NOT NULL, maintenance_source_id CHAR(36) NOT NULL,"
        + "source_version BIGINT NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
        + "FOREIGN KEY(application_id) REFERENCES room_applications(id))");
    // Retain original M1 receipts so an already acknowledged event can be replayed safely.
    execute(connection, "CREATE TABLE IF NOT EXISTS application_resource_events ("
        + "event_id CHAR(36) PRIMARY KEY, application_id CHAR(36) NOT NULL, fingerprint CHAR(64) NOT NULL,"
        + "created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),"
        + "FOREIGN KEY(application_id) REFERENCES room_applications(id))");
    if (!hasColumn(connection, "application_notification_tasks", "application_id")) {
      execute(connection, "ALTER TABLE application_notification_tasks ADD COLUMN application_id CHAR(36) NULL");
      execute(connection, "UPDATE application_notification_tasks SET application_id=("
          + "SELECT id FROM room_applications WHERE application_notification_tasks.event_key="
          + "CONCAT('MAINTENANCE_CANCELLED:',room_applications.id))"
          + " WHERE application_id IS NULL AND event_key LIKE 'MAINTENANCE_CANCELLED:%'");
    }
    if (!hasColumn(connection, "application_notification_tasks", "created_at"))
      execute(connection, "ALTER TABLE application_notification_tasks ADD COLUMN created_at TIMESTAMP(6)"
          + " NOT NULL DEFAULT CURRENT_TIMESTAMP(6)");
    if (!hasIndex(connection, "application_notification_tasks", "ix_application_notifications_pending"))
      execute(connection, "CREATE INDEX ix_application_notifications_pending"
          + " ON application_notification_tasks(status,next_attempt)");
  }

  private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
    try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, column)) {
      return columns.next();
    }
  }

  private boolean hasIndex(Connection connection, String table, String index) throws SQLException {
    try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, false, false)) {
      while (indexes.next()) if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
      return false;
    }
  }

  private void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) { statement.execute(sql); }
  }
}
