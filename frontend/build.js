/**
 * Build del bundle CodeMirror 6 → src/main/resources/static/js/editor.js
 * Uso: node frontend/build.js   (dopo npm install)
 */
const esbuild = require('esbuild');
const path = require('path');

esbuild.buildSync({
  entryPoints: [path.join(__dirname, 'editor-main.js')],
  bundle: true,
  minify: true,
  format: 'iife',
  target: 'es2020',
  outfile: path.join(__dirname, '..', 'pdfornotpdf', 'src', 'main', 'resources', 'static', 'js', 'editor.js'),
  logLevel: 'info',
});
