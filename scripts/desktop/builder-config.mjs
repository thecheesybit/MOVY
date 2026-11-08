// electron-builder configuration derived from the resolved app config. Pure, unit tested.
import path from 'node:path';

export function electronBuilderConfig(cfg, { outDir, gen }) {
  return {
    appId: cfg.appId,
    productName: cfg.appName,
    extraMetadata: { name: `movy-${cfg.name}`, version: cfg.version, main: 'src/main/main.js' },
    directories: { output: outDir },
    files: ['package.json', 'src/**/*', '!**/node_modules/**'], // the build-only root dependencies stay out of the app
    extraResources: [{ from: gen, to: 'movy' }], // engine, config, .node: outside the asar
    asar: true,
    npmRebuild: false,
    nodeGypRebuild: false,
    win: {
      icon: path.join(gen, 'icon.png'),
      target: [{ target: 'nsis', arch: 'x64' }, { target: 'dir', arch: 'x64' }],
      artifactName: '${productName}-Setup-${version}.${ext}',
    },
    nsis: {
      oneClick: false,
      perMachine: false,
      allowToChangeInstallationDirectory: true,
      deleteAppDataOnUninstall: false, // logins/settings survive uninstall, reinstall and updates
      shortcutName: cfg.appName,
    },
  };
}
