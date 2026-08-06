import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// PayFlow React frontend — dev server on 8081 (add this origin to the backend's
// app.cors.allowed-origins if it isn't already present).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 8081,
  },
});

