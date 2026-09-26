/** Generates reviewed SQL migrations from the local application schema. */
import { defineConfig } from 'drizzle-kit';
export default defineConfig({ dialect: 'postgresql', schema: './src/schema.ts', out: './migrations' });
