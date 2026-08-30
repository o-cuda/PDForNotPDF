/**
 * PDForNotPDF — bundle CodeMirror 6 precompilato.
 *
 * Build: node frontend/build.js  → src/main/resources/static/js/editor.js
 * Il bundle è committato: nessun build a runtime, nessun CDN per l'editor.
 * Espone window.CM6 con le primitive usate da workspace.html.
 */
const {
  EditorView,
  keymap,
  lineNumbers,
  highlightActiveLine,
  highlightActiveLineGutter,
  highlightSpecialChars,
  drawSelection,
  dropCursor,
  rectangularSelection,
  crosshairCursor,
  EditorView: _unused,
} = require('@codemirror/view');
const { EditorState } = require('@codemirror/state');
const { indentWithTab } = require('@codemirror/commands');
const { basicSetup } = require('codemirror');
const { html } = require('@codemirror/lang-html');
const { css } = require('@codemirror/lang-css');
const { json, jsonParseLinter } = require('@codemirror/lang-json');
const { linter } = require('@codemirror/lint');
const { HighlightStyle, syntaxHighlighting } = require('@codemirror/language');
const { tags: t } = require('@lezer/highlight');

// ===== Tema Catppuccin Mocha (coerente con l'interfaccia dell'app) =====
const editorTheme = EditorView.theme(
  {
    '&': { background: '#1e1e2e', color: '#cdd6f4', fontSize: '13px', height: '100%' },
    '&.cm-focused': { outline: 'none' },
    '.cm-scroller': { fontFamily: "'JetBrains Mono', 'Cascadia Code', 'Fira Code', monospace", lineHeight: '1.55' },
    '.cm-gutters': { background: '#181825', color: '#6c7086', border: 'none', borderRight: '1px solid #313244' },
    '.cm-activeLineGutter': { background: '#313244', color: '#cba6f7' },
    '.cm-activeLine': { background: '#18182580' },
    '&.cm-focused .cm-selectionBackground, .cm-selectionBackground, ::selection': { background: '#45475a' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#cba6f7', borderLeftWidth: '2px' },
    '.cm-selectionMatch': { background: '#585b7066' },
    '.cm-searchMatch': { background: '#f9e2af40', outline: '1px solid #f9e2af80' },
    '.cm-searchMatch.cm-searchMatch-selected': { background: '#f9e2af66' },
    '.cm-tooltip': { background: '#313244', border: '1px solid #45475a', color: '#cdd6f4' },
    '.cm-tooltip-autocomplete ul li[aria-selected]': { background: '#45475a', color: '#cba6f7' },
    '.cm-panels': { background: '#181825', color: '#cdd6f4' },
    '.cm-panels.cm-panels-bottom': { borderTop: '1px solid #313244' },
    '.cm-foldPlaceholder': { background: '#45475a', border: 'none', color: '#a6adc8' },
  },
  { dark: true }
);

const highlight = HighlightStyle.define([
  { tag: t.comment, color: '#6c7086', fontStyle: 'italic' },
  { tag: [t.keyword, t.controlKeyword, t.moduleKeyword], color: '#cba6f7' },
  { tag: [t.string, t.special(t.string), t.attributeValue], color: '#a6e3a1' },
  { tag: [t.number, t.bool, t.null, t.atom], color: '#fab387' },
  { tag: [t.tagName], color: '#f38ba8' },
  { tag: [t.attributeName], color: '#f9e2af' },
  { tag: [t.propertyName], color: '#89b4fa' },
  { tag: [t.function(t.variableName), t.function(t.propertyName)], color: '#89dceb' },
  { tag: [t.typeName, t.className], color: '#89b4fa' },
  { tag: [t.operator, t.compareOperator, t.logicOperator], color: '#94e2d5' },
  { tag: [t.punctuation, t.separator, t.bracket], color: '#a6adc8' },
  { tag: [t.meta, t.processingInstruction], color: '#f5c2e7' },
  { tag: [t.link, t.url], color: '#89b4fa', textDecoration: 'underline' },
  { tag: [t.heading], color: '#cba6f7', fontWeight: 'bold' },
  { tag: [t.emphasis], fontStyle: 'italic' },
  { tag: [t.strong], fontWeight: 'bold' },
  { tag: [t.invalid], color: '#f38ba8' },
]);

function languageFor(kind) {
  if (kind === 'html') return html();
  if (kind === 'css') return css();
  if (kind === 'json') return json();
  return [];
}

/**
 * Estensioni per un documento. onChange riceve l'update CM6 a ogni modifica;
 * onSave è invocato da Ctrl/Cmd+S quando il focus è dentro l'editor.
 */
function extensionsFor(kind, handlers = {}) {
  const ext = [
    basicSetup,
    editorTheme,
    syntaxHighlighting(highlight),
    EditorView.lineWrapping,
    keymap.of([indentWithTab]),
  ];
  ext.push(languageFor(kind));
  if (kind === 'json') ext.push(linter(jsonParseLinter()));
  if (handlers.onChange) ext.push(EditorView.updateListener.of((u) => { if (u.docChanged) handlers.onChange(u); }));
  ext.push(keymap.of([{ key: 'Mod-s', run: () => { if (handlers.onSave) handlers.onSave(); return true; } }]));
  return ext;
}

function makeState(kind, doc, handlers) {
  return EditorState.create({ doc, extensions: extensionsFor(kind, handlers) });
}

window.CM6 = {
  EditorView,
  EditorState,
  makeState,
  extensionsFor,
  languageFor,
  version: '1.0.0',
};
