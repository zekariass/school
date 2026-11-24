import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// Only pure TypeScript modules are unit-tested here: no react-native or expo
// imports in tested files (those need the device runtime).
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
