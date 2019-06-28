const glob = require("glob");
const Path = require("path");
const fs = require("fs");
const { execSync } = require('child_process');

// directories
const dirs = {}
dirs.project = Path.resolve(__dirname, '../../../..');
dirs.root = Path.resolve(dirs.project, '..');
dirs.assets = Path.join(dirs.project, 'assets');

// initialize module exports
const webpack = require(Path.join(__dirname, 'scalajs.webpack.config'));
const appName = Object.keys(webpack.entry)[0];
webpack.plugins = webpack.plugins || [];
webpack.module = webpack.module || {};
webpack.module.rules = webpack.module.rules || [];

// TODO bug with custom output in https://github.com/scalacenter/scalajs-bundler/issues/192
// expects the originally configured output file to exist, just create it.
const dummyOutputFile = Path.join(webpack.output.path, webpack.output.filename.replace('[name]', appName));
if (!fs.existsSync(dummyOutputFile)) {
    fs.closeSync(fs.openSync(dummyOutputFile, 'w'));
}

// gather resources
const cssFolder = Path.join(dirs.project, 'src/css');
const htmlFolder = Path.join(dirs.project, 'src/html');
const jsFolder = Path.join(dirs.project, 'src/js');
const cssFiles = glob.sync(Path.join(cssFolder, '*.css'));
const htmlTemplateFile = Path.join(htmlFolder, 'index.template.html');

// copy static assets that we depend on
const staticCopyAssets = [
    { from: Path.join(htmlFolder, 'error-*.html'), context: htmlFolder, to: ''},
    { from: 'node_modules/emoji-datasource-twitter/img/twitter/sheets/64.png', to: 'emoji-datasource/sheet_twitter_64.png'},
    { from: 'node_modules/highlight.js/styles/github-gist.css', to: 'highlight/github-gist.css'},
    { from: 'node_modules/jquery/dist/jquery.min.js', to: 'jquery.min.js'},
    { from: 'node_modules/jquery-tablesort/jquery.tablesort.min.js', to: 'jquery.tablesort.min.js'},
    { from: 'node_modules/clipboard/dist/clipboard.min.js', to: 'clipboard.min.js'},
    { from: 'node_modules/emoji-js/lib/emoji.min.js', to: 'emoji.min.js'},
    { from: 'node_modules/fomantic-ui-css/semantic.min.css', to: 'semantic/' },
    { from: 'node_modules/fomantic-ui-css/semantic.min.js', to: 'semantic/' },
    { from: 'node_modules/fomantic-ui-css/themes/default/assets/fonts/icons.woff2', to: 'semantic/themes/default/assets/fonts/' },
    { from: 'node_modules/wdt-emoji-bundle/emoji.min.js', to: 'emoji-picker/emoji.min.js' },
    { from: 'node_modules/wdt-emoji-bundle/wdt-emoji-bundle.min.js', to: 'emoji-picker/wdt-emoji-bundle.min.js' },
    { from: 'node_modules/wdt-emoji-bundle/wdt-emoji-bundle.css', to: 'emoji-picker/wdt-emoji-bundle.css' },
    { from: 'node_modules/wdt-emoji-bundle/sheets/sheet_twitter_64_indexed_128.png', to: 'emoji-picker/sheets/sheet_twitter_64_indexed_128.png' },
    { from: 'node_modules/tributejs/dist/tribute.css', to: 'tributejs/tribute.css' },
];
const staticIncludeAssets = [
    'jquery.min.js',
    'jquery.tablesort.min.js',
    'clipboard.min.js',
    'emoji.min.js',
    'semantic/semantic.min.js' ,
    'semantic/semantic.min.css' ,
    'highlight/github-gist.css',
    'emoji-picker/emoji.min.js',
    'emoji-picker/wdt-emoji-bundle.min.js',
    'emoji-picker/wdt-emoji-bundle.css',
    'tributejs/tribute.css',
];

const woostVersion = process.env.WUST_VERSION ? process.env.WUST_VERSION : "latest";

// export
module.exports.webpack = webpack;
module.exports.woost = {
    appName: appName,
    dirs: dirs,
    cssFolder: cssFolder,
    cssFiles: cssFiles,
    htmlTemplateFile: htmlTemplateFile,
    staticCopyAssets: staticCopyAssets,
    staticIncludeAssets: staticIncludeAssets,
    versionString: woostVersion
};
