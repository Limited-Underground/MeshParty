import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

const schema = readFileSync(new URL("../migrations/0001_create_reports.sql", import.meta.url), "utf8");

// Run the Worker's actual D1 statements on a local SQLite database without provisioning a service.
export function createSqliteD1(file = ":memory:") {
  const db = new DatabaseSync(file);
  if (!db.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'abuse_reports'").get()) {
    db.exec(schema);
  }

  const binding = {
    prepare(sql) {
      const bind = (...values) => {
        const execute = () => {
          const statement = db.prepare(sql);
          const parameters = values.length
            ? [Object.fromEntries(values.map((value, index) => ["?" + (index + 1), value]))]
            : [];
          if (statement.columns().length) {
            return { success: true, results: statement.all(...parameters) };
          }
          return { success: true, meta: { changes: statement.run(...parameters).changes } };
        };
        return { execute, run: async () => execute() };
      };
      return { ...bind(), bind };
    },
    async batch(statements) {
      db.exec("BEGIN");
      try {
        const results = statements.map(statement => statement.execute());
        db.exec("COMMIT");
        return results;
      } catch (error) {
        db.exec("ROLLBACK");
        throw error;
      }
    },
  };
  return { db, binding };
}
