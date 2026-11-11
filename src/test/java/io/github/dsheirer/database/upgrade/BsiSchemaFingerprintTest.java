package io.github.dsheirer.database.upgrade;
import io.github.dsheirer.database.*;
import org.junit.jupiter.api.Test;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;
class BsiSchemaFingerprintTest {
 @Test void freshHistory() throws Exception {
  try(var c=DriverManager.getConnection("jdbc:sqlite::memory:")) {
   ConfigurationHistorySchema.createHistory(c);
   assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(c));
  }
 }
}
