import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In dev, forward API calls to the Java service on :8080.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
});
