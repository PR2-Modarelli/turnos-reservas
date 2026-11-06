#!/usr/bin/env python3
"""Genera la clave privada de PostgreSQL una vez; no imprime secretos ni toca Docker."""
from pathlib import Path
import os
import re
import secrets
import shlex
import tempfile


def preparar(destino):
    texto = destino.read_text() if destino.exists() else ""
    patron = re.compile(r"^(?:export +)?TURNOS_DB_PASSWORD=(.*)$", re.MULTILINE)
    encontrados = list(patron.finditer(texto))
    if len(encontrados) > 1:
        raise ValueError("TURNOS_DB_PASSWORD esta definida mas de una vez; revisar el archivo local")
    if encontrados:
        valor = shlex.split(encontrados[0].group(1), comments=True)
        if valor and valor[0]:
            destino.chmod(0o600)
            return False
    nueva = "TURNOS_DB_PASSWORD=" + secrets.token_urlsafe(32)
    if encontrados:
        texto = patron.sub(nueva, texto)
    else:
        texto = texto.rstrip() + "\n" + nueva + "\n"
    temporal = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=destino.parent, delete=False) as archivo:
            temporal = Path(archivo.name)
            archivo.write(texto)
            os.fchmod(archivo.fileno(), 0o600)
        os.replace(temporal, destino)
    finally:
        if temporal and temporal.exists():
            temporal.unlink()
    return True


if __name__ == "__main__":
    destino = Path(__file__).resolve().parent.parent / ".env"
    try:
        cambio = preparar(destino)
        print("Configuracion privada de PostgreSQL preparada." if cambio else "Se conserva la configuracion privada existente.")
    except (OSError, ValueError) as error:
        raise SystemExit("No se pudo preparar la configuracion local; revisar el archivo .env.") from None
