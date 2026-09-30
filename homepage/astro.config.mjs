// @ts-check
import { defineConfig } from 'astro/config';

// https://astro.build/config
export default defineConfig({
  output: 'static',
  devToolbar: { enabled: false },
  site: process.env.PUBLIC_SITE_URL,
});
