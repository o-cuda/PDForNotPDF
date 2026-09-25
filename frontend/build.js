/**
 * Build dei bundle esbuild:
 *  - frontend/editor-main.js  → static/js/editor.js   (CodeMirror 6)
 *  - frontend/workbench-main.js → static/js/workbench.js (logica del workbench, estratta dal template — R1)
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

esbuild.buildSync({
  entryPoints: [path.join(__dirname, 'workbench-main.js')],
  bundle: true,
  minify: false, // leggibile: la diagnostica in campo è più semplice
  format: 'iife',
  target: 'es2020',
  outfile: path.join(__dirname, '..', 'pdfornotpdf', 'src', 'main', 'resources', 'static', 'js', 'workbench.js'),
  logLevel: 'info',
});
