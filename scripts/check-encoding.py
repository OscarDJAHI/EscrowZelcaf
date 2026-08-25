#!/usr/bin/env python3
"""Garde d'encodage du dépôt : aucun octet NUL, aucun octet UTF-8 invalide.

POURQUOI CETTE GARDE EXISTE
---------------------------
Deux fois dans ce dépôt, un littéral à octets bruts a rendu un fichier source
*binaire pour git* sans casser la compilation ni rougir la suite de tests :

  - `frontend/src/views/AuthView.vue` — classe de caractères de contrôle écrite
    en octets bruts (Story 1.9, trouvé à la 2e revue de suivi) ;
  - `backend/.../service/EvidenceServiceTest.java` — en-tête magique GIF écrit
    en octets bruts (1 200 lignes, trouvé au balayage du ledger 2026-07-27).

Les conséquences ne sont pas cosmétiques : `git` traite le fichier en binaire
(non diffable, non cherry-pickable, pas de fusion 3-way), et surtout **`grep` ne
voit plus une seule ligne du fichier**. Or les greps de vérification sont la
preuve sur laquelle chaque story de ce projet s'appuie : un fichier invisible à
`grep` est un fichier dont on ne peut plus rien prouver.

Le second cas a survécu au premier parce qu'aucune barrière automatisée ne
regardait. C'est cette barrière.

PÉRIMÈTRE
---------
Fichiers **suivis par git** uniquement — c'est la surface qui est partagée, et
c'est celle où le défaut a mordu. Les fichiers marqués `binary` dans
`.gitattributes` sont exclus ; tout autre fichier est tenu d'être du texte
UTF-8 valide, y compris ceux dont l'extension est inconnue (voir le commentaire
de `.gitattributes` sur le choix dénylist).

Sortie : 0 si tout est propre, 1 sinon (avec le chemin, l'offset, la ligne et
les octets fautifs — un message dont on peut agir sans enquête).
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


def repo_root() -> Path:
    out = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        capture_output=True,
        text=True,
        check=True,
    )
    return Path(out.stdout.strip())


def tracked_files(root: Path) -> list[str]:
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=root, capture_output=True, check=True
    )
    return [p for p in out.stdout.decode("utf-8", "surrogateescape").split("\0") if p]


def binary_marked(root: Path, paths: list[str]) -> set[str]:
    """Chemins portant l'attribut `binary` dans .gitattributes.

    `git check-attr -z --stdin` répond par triplets `chemin\\0attribut\\0valeur\\0`,
    dans l'ordre d'entrée. On lit la valeur, pas la présence : `unspecified` et
    `unset` signifient tous deux « ce fichier doit être du texte ».
    """
    if not paths:
        return set()
    out = subprocess.run(
        ["git", "check-attr", "-z", "--stdin", "binary"],
        cwd=root,
        input="\0".join(paths).encode("utf-8", "surrogateescape"),
        capture_output=True,
        check=True,
    )
    fields = out.stdout.decode("utf-8", "surrogateescape").split("\0")
    marked = set()
    for i in range(0, len(fields) - 2, 3):
        path, _attr, value = fields[i], fields[i + 1], fields[i + 2]
        if value == "set":
            marked.add(path)
    return marked


def line_of(data: bytes, offset: int) -> int:
    return data.count(b"\n", 0, offset) + 1


def context_of(data: bytes, offset: int, radius: int = 24) -> str:
    chunk = data[max(0, offset - radius) : offset + radius]
    return repr(chunk)


def inspect(path: Path) -> tuple[str, int] | None:
    """Retourne (raison, offset) au premier défaut trouvé, sinon None."""
    data = path.read_bytes()

    nul = data.find(b"\0")
    if nul >= 0:
        return ("octet NUL", nul)

    try:
        data.decode("utf-8")
    except UnicodeDecodeError as err:
        return (f"UTF-8 invalide ({err.reason})", err.start)

    return None


def main() -> int:
    root = repo_root()
    paths = tracked_files(root)
    skip = binary_marked(root, paths)

    findings: list[str] = []
    checked = 0

    for rel in paths:
        if rel in skip:
            continue
        path = root / rel
        # Un fichier suivi mais absent du disque (checkout partiel, suppression
        # non commitée) n'est pas un défaut d'encodage — on n'invente pas un
        # échec sur une absence.
        if not path.is_file() or path.is_symlink():
            continue
        checked += 1
        found = inspect(path)
        if found is None:
            continue
        reason, offset = found
        data = path.read_bytes()
        findings.append(
            f"  {rel}\n"
            f"    {reason} à l'octet {offset} (ligne {line_of(data, offset)})\n"
            f"    contexte : {context_of(data, offset)}"
        )

    if findings:
        print(
            f"ERREUR : {len(findings)} fichier(s) suivi(s) ne sont pas du texte "
            f"UTF-8 valide.\n",
            file=sys.stderr,
        )
        print("\n".join(findings), file=sys.stderr)
        print(
            "\nUn fichier portant un octet NUL est BINAIRE pour git : invisible à "
            "`grep`,\nnon diffable, non fusionnable en 3-way. Remède : remplacer le "
            "littéral à\noctets bruts par un tableau d'octets explicite "
            "(ex. `{'G','I','F', 0x01, ...}`)\nou une séquence d'échappement que le "
            "compilateur interprète — jamais l'octet lui-même.\n"
            "Si le fichier est légitimement binaire, le déclarer dans "
            "`.gitattributes`.",
            file=sys.stderr,
        )
        return 1

    print(f"Encodage : {checked} fichiers suivis vérifiés, aucun octet NUL, UTF-8 valide.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
