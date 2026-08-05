import { hapTasks } from '@ohos/hvigor-ohos-plugin';
import * as fs from 'fs';
import * as path from 'path';

export default {
  system: hapTasks, /* Built-in plugin of Hvigor. It cannot be modified. */
  plugins: [kuiklyPullOhosProduct(), kuiklyCopyAssets()]   /* Custom plugin to extend the functionality of Hvigor. */
}

// 用于首次拉取ohos产物
function kuiklyPullOhosProduct(): HvigorPlugin {
  return {
    pluginId: 'kuiklyPullOhosProductPlugin',
    apply(node: HvigorNode) {
      node.registerTask({
        name: 'kuikly_pull_ohos_product',
        run: () => {
          const networkModuleDir = path.join(
            node.getNodePath(), 'oh_modules', '@catchzoon', 'network-ohos');
          const soDir = path.join(networkModuleDir, 'libs', 'arm64-v8a');
          const soFile = path.join(soDir, 'libshared.so');
          const apiDir = path.join(networkModuleDir, 'include');
          const apiFile = path.join(apiDir, 'libshared_api.h');
          if (!fs.existsSync(soFile) || !fs.existsSync(apiFile)) {
            throw new Error(
              `Kuikly Network HAR product is missing. Run ./scripts/build_harmony.sh har and ohpm install first.\n` +
              `Expected SO: ${soFile}\nExpected header: ${apiFile}`
            );
          }
        },
        postDependencies: ['default@PreBuild']
      })
    }
  }
}

// 编译时copy assets
function kuiklyCopyAssets(): HvigorPlugin {
  return {
    pluginId: 'kuiklyCopyAssetsPlugin',
    apply(node: HvigorNode) {
      node.registerTask({
        name: 'kuikly_copy_assets',
        run: (taskContext) => {
          console.log('kuikly copy assets start');
          const sourceDir = path.join(node.getNodePath(),
            '..', '..', 'demo', 'src', 'commonMain', 'assets');
          const destDir = path.join(node.getNodePath(),
            'build', 'default', 'intermediates', 'res', 'default', 'resources', 'resfile');
          console.log(`assets file copy from: ${sourceDir}`);
          console.log(`assets file copy to: ${destDir}`);
          if (!fs.existsSync(sourceDir)) {
            console.log('kuikly assets directory is absent, skipping copy');
            return;
          }
          if (!fs.existsSync(destDir)) {
            fs.mkdirSync(destDir, { recursive: true });
          }
          fs.cpSync(sourceDir, destDir, {
            recursive: true,
            force: true,
          });
          console.log('kuikly copy assets finish');
        },
        dependencies: [`default@CompileResource`],
        postDependencies: [`default@CompileArkTS`]
      })
    }
  }
}
