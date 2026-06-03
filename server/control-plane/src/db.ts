import pg from "pg";

const { Pool } = pg;

export const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 10,
});

export async function query<T extends pg.QueryResultRow = any>(
  text: string,
  params: unknown[] = []
): Promise<pg.QueryResult<T>> {
  return pool.query<T>(text, params);
}

export async function audit(
  actor: string,
  action: string,
  deviceId: string | null,
  detail: Record<string, unknown> = {}
): Promise<void> {
  await query(
    `INSERT INTO audit_log (device_id, actor, action, detail) VALUES ($1, $2, $3, $4)`,
    [deviceId, actor, action, JSON.stringify(detail)]
  );
}
