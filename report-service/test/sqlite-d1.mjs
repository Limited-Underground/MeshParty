import { readFileSync } from "node:fs";
import { DatabaseSync } from "node:sqlite";

const migrations = [
  ["abuse_reports", "0001_create_reports.sql"],
  ["feedback", "0002_create_feedback.sql"],
];

// Run the Worker's actual D1 statements on a local SQLite database without provisioning a service.
export function createSqliteD1(file = ":memory:") {
  const db = new DatabaseSync(file);
  const tableExists = db.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?");
  for (const [table, migration] of migrations) {
    if (!tableExists.get(table)) {
      db.exec(readFileSync(new URL(`../migrations/${migration}`, import.meta.url), "utf8"));
    }
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
