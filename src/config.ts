/** Runtime configuration; secrets are read only by the server and local scripts. */
import { z } from 'zod';
export const Config = z.object({
  DATABASE_URL: z.string().url(), API_TOKEN: z.string().min(24), PORT: z.coerce.number().default(8787), HOST: z.string().default('127.0.0.1'),
  S3_ENDPOINT: z.string().url(), S3_PUBLIC_ENDPOINT: z.string().url(), S3_ACCESS_KEY: z.string().min(1),
  S3_SECRET_KEY: z.string().min(24), S3_BUCKET: z.string().min(1), S3_REGION: z.string().default('us-east-1'),
});
/** Loads required settings without printing secret values on validation failure. */
export function config() {
  const result = Config.safeParse(process.env);
  if (!result.success) throw new Error('Invalid configuration keys: ' + result.error.issues.map(i => i.path.join('.')).join(', '));
  return result.data;
}
