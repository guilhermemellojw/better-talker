import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.bettertalker.app',
  appName: 'Better Talker',
  webDir: 'dist',
  server: {
    androidScheme: 'https'
  }
};

export default config;
