import { Capacitor, registerPlugin } from '@capacitor/core';

const isNative = (): boolean => Capacitor.isNativePlatform();

export const PublicationBridge = registerPlugin('PublicationBridge');

export async function abrirNavegadorParaDownload(url: string): Promise<void> {
  if (!isNative()) {
    window.open(url, '_blank');
    return;
  }
  try {
    await (PublicationBridge as any).abrirNavegadorParaDownload({ url });
  } catch {
    window.open(url, '_blank');
  }
}

export function setupDownloadListener(onDownloaded: (status: string, path: string) => void): void {
  if (isNative()) return;
  (window as any).onPublicacaoBaixada = (status: string, path: string) => {
    onDownloaded(status, path);
  };
}

export function isNativePlatform(): boolean {
  return isNative();
}
