#!/usr/bin/env python3
"""
Migra un workspace PDForNotPDF dal formato precedente al nuovo.

Vecchio formato:                 Nuovo formato:
  <ws>/CLIENTE_A/…                 <ws>/snapshot/CLIENTE_A/…
  <ws>/STANDARD/…                  <ws>/snapshot/STANDARD/…
  <ws>/assets/…                    <ws>/snapshot/assets/…
  <ws>/.git          (bozze)       <ws>/.git          (repo unico)
  <ws>/releases/.git (releases)    <ws>/release/…     (solo contenuti; .git archiviato)

Uso: python3 tools/migrate-workspace.py <percorso-workspace>

Idempotente: se il workspace è già nel nuovo formato non fa nulla.
"""

import os
import shutil
import sys

GIT_ENTRIES = {'.git', '.gitignore', '.gitattributes'}


def is_legacy(ws):
    if os.path.isdir(os.path.join(ws, 'snapshot')):
        return False
    for dirpath, dirnames, filenames in os.walk(ws):
        dirnames[:] = [d for d in dirnames if d not in ('.git', 'releases', 'release', 'snapshot')]
        if any(f.lower().endswith('.html') for f in filenames):
            return True
    return False


def migrate(ws):
    ws = os.path.abspath(ws)
    if not os.path.isdir(ws):
        raise SystemExit(f"❌ Percorso inesistente: {ws}")

    if os.path.isdir(os.path.join(ws, 'snapshot')):
        print(f"✓ Workspace già nel nuovo formato: {ws}")
        return

    if not is_legacy(ws):
        raise SystemExit(f"❌ Nessun template .html trovato: non sembra un workspace PDForNotPDF ({ws})")

    snapshot = os.path.join(ws, 'snapshot')
    os.makedirs(snapshot, exist_ok=True)

    # 1. sposta i contenuti di lavoro in snapshot/
    for name in sorted(os.listdir(ws)):
        if name in GIT_ENTRIES or name in ('snapshot', 'release', 'releases'):
            continue
        src, dst = os.path.join(ws, name), os.path.join(snapshot, name)
        if os.path.exists(dst):
            raise SystemExit(f"❌ Destinazione già esistente, migrazione interrotta: snapshot/{name}")
        shutil.move(src, dst)
        print(f"  → snapshot/{name}")

    # 2. releases/ diventa release/
    old_releases = os.path.join(ws, 'releases')
    new_release = os.path.join(ws, 'release')
    if os.path.isdir(old_releases):
        nested_git = os.path.join(old_releases, '.git')
        if os.path.isdir(nested_git):
            archived = os.path.join(old_releases, '.git.old')
            if os.path.exists(archived):
                shutil.rmtree(archived)
            os.rename(nested_git, archived)
            print("  → releases/.git archiviato in releases/.git.old (storia disponibile a mano)")
        if os.path.isdir(new_release):
            # release/ già presente (raro): unisci i contenuti
            for name in os.listdir(old_releases):
                if name == '.git.old':
                    continue
                src, dst = os.path.join(old_releases, name), os.path.join(new_release, name)
                if os.path.exists(dst):
                    raise SystemExit(f"❌ Conflitto in migrazione: {dst} esiste già")
                shutil.move(src, dst)
            shutil.rmtree(old_releases, ignore_errors=True)
        else:
            os.rename(old_releases, new_release)
        print("  → releases/ rinominata in release/")

    # 3. .gitignore: release/ ora è tracciata (niente eccezioni)
    gitignore = os.path.join(ws, '.gitignore')
    if os.path.exists(gitignore):
        with open(gitignore, encoding='utf-8') as f:
            lines = f.read().splitlines()
        kept = [ln for ln in lines if ln.strip() not in ('releases/', 'release/', '/releases/', '/release/')]
        if kept != lines:
            with open(gitignore, 'w', encoding='utf-8') as f:
                f.write('\n'.join(kept) + ('\n' if kept else ''))
            print("  → .gitignore aggiornato (release/ tracciata)")

    print(f"\n✓ Migrazione completata: {ws}")
    print("  Ora ricarica il workspace nell'app. Al prossimo salvataggio verrà creato/aggiornato il repo unico.")


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    migrate(sys.argv[1])
