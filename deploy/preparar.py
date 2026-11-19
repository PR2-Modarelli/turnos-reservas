"""Prepara secretos y certificados locales sin imprimir credenciales ni sobrescribirlos."""
import base64
import os
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parent
private = root / "private"
private.mkdir(mode=0o700, exist_ok=True)
envfile = private / ".env"
if envfile.exists():
    print("La configuración privada ya existe; se conserva. Ver deploy/README.md para renovarla.")
    raise SystemExit(0)
if any(private.iterdir()):
    raise SystemExit("Hay una preparación incompleta en deploy/private; revisar antes de continuar.")

def env(name, default=""):
    return os.environ.get(name) or default

def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

jwt = env("TURNOS_JWT_SECRET", env("CATALOGO_JWT_SECRET"))
try:
    if len(base64.b64decode(jwt, validate=True)) < 64:
        raise ValueError()
except ValueError:
    raise SystemExit("Falta una clave JWT Base64 de al menos 64 bytes en el entorno privado.")
password = secrets.token_hex(24)
os.environ["PR2_TLS_PASSWORD"] = password
ca = private / "ca.pem"
try:
    run("openssl", "req", "-x509", "-newkey", "rsa:3072", "-nodes", "-days", "365", "-subj", "/CN=PR2 desarrollo local", "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign", "-keyout", str(private / "ca.key"), "-out", str(ca))
    for service in ("catalogo", "turnos"):
        key, csr, crt = [private / (service + ext) for ext in (".key", ".csr", ".pem")]
        ext = private / (service + ".ext")
        ext.write_text(f"subjectAltName=DNS:{service},DNS:localhost,IP:127.0.0.1,IP:10.0.2.2\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
        run("openssl", "req", "-newkey", "rsa:3072", "-nodes", "-subj", f"/CN={service}", "-keyout", str(key), "-out", str(csr))
        run("openssl", "x509", "-req", "-in", str(csr), "-CA", str(ca), "-CAkey", str(private / "ca.key"), "-CAcreateserial", "-days", "90", "-extfile", str(ext), "-out", str(crt))
        run("openssl", "pkcs12", "-export", "-name", "application", "-inkey", str(key), "-in", str(crt), "-certfile", str(ca), "-out", str(private / (service + ".p12")), "-passout", "env:PR2_TLS_PASSWORD")
        (private / (service + ".p12")).chmod(0o644)
    # Mantener también las CA públicas, para no romper otras integraciones HTTPS.
    cacerts = Path(shutil.which("java")).resolve().parent.parent / "lib/security/cacerts"
    run("keytool", "-importkeystore", "-noprompt", "-srckeystore", str(cacerts),
        "-srcstorepass", "changeit", "-destkeystore", str(private / "truststore.p12"),
        "-deststoretype", "PKCS12", "-deststorepass", "changeit")
    # Solo contiene certificados públicos. Su contraseña no protege claves privadas.
    run("keytool", "-importcert", "-noprompt", "-alias", "pr2-local", "-file", str(ca), "-keystore", str(private / "truststore.p12"), "-storetype", "PKCS12", "-storepass", "changeit")
    (private / "truststore.p12").chmod(0o644)
    ca.chmod(0o644)
    group = env("TURNOS_KAFKA_GROUP_ID", env("CATALOGO_KAFKA_CATALOG_TOPIC").removeprefix("catedra.catalog."))
    values = {
        "APP_JWT_SECRET": jwt, "TLS_PASSWORD": password,
        "CATALOGO_DB_PASSWORD": secrets.token_hex(24), "TURNOS_DB_PASSWORD": secrets.token_hex(24),
        "CATEDRA_BASE_URL": env("CATEDRA_BASE_URL", env("CATALOGO_CATEDRA_BASE_URL")),
        "CATEDRA_TOKEN": env("CATEDRA_TOKEN", env("CATALOGO_CATEDRA_TOKEN")),
        "CATALOGO_SYNC_ENABLED": env("CATALOGO_SYNC_ENABLED", "true"),
        "CATALOGO_REDIS_HOST": env("CATALOGO_REDIS_HOST"),
        "CATALOGO_REDIS_PORT": env("CATALOGO_REDIS_PORT", "6379"),
        "CATALOGO_REDIS_USERNAME": env("CATALOGO_REDIS_USERNAME"),
        "CATALOGO_REDIS_PASSWORD": env("CATALOGO_REDIS_PASSWORD"),
        "CATALOGO_KAFKA_BOOTSTRAP_SERVERS": env("CATALOGO_KAFKA_BOOTSTRAP_SERVERS"),
        "CATALOGO_KAFKA_CATALOG_TOPIC": env("CATALOGO_KAFKA_CATALOG_TOPIC"),
        "CATALOGO_KAFKA_CONSUMER_GROUP": env("COMPOSE_CATALOGO_CONSUMER_GROUP", env("CATALOGO_KAFKA_CONSUMER_GROUP", "catalogo") + "-compose"),
        "TURNOS_KAFKA_ENABLED": env("TURNOS_KAFKA_ENABLED", "true"),
        "TURNOS_KAFKA_BOOTSTRAP_SERVERS": env("TURNOS_KAFKA_BOOTSTRAP_SERVERS", env("CATALOGO_KAFKA_BOOTSTRAP_SERVERS")),
        "TURNOS_KAFKA_GROUP_ID": group,
        "TURNOS_KAFKA_CONSUMER_GROUP": env("COMPOSE_TURNOS_CONSUMER_GROUP", "turnos-compose-" + group),
        "TURNOS_KAFKA_ACTIONS_TOPIC": env("TURNOS_KAFKA_ACTIONS_TOPIC", "alumnos.turnos.acciones." + group),
        "TURNOS_KAFKA_PHONE_TOPIC": env("TURNOS_KAFKA_PHONE_TOPIC", "catedra.turnos.telefono." + group),
    }
    def quote(value):
        if "\n" in value or "\r" in value:
            raise ValueError("No se admiten saltos de línea en configuración")
        return "'" + value.replace("'", "\\'") + "'"
    envfile.write_text("\n".join(k + "=" + quote(v) for k, v in values.items()) + "\n")
    envfile.chmod(0o600)
except (subprocess.CalledProcessError, ValueError):
    raise SystemExit("No se pudo preparar el despliegue. Revisar dependencias y deploy/private sin publicar su contenido.")
print("Configuración y certificados locales preparados en deploy/private. No se publican en Git.")
