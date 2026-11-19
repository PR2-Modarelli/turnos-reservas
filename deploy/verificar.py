"""Prueba local HTTPS, JWT e integración sin crear reservas en la cátedra."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import json
from pathlib import Path
import secrets
import ssl
import urllib.error
import urllib.request

private = Path(__file__).resolve().parent / "private"
context = ssl.create_default_context(cafile=str(private / "ca.pem"))
fixture = private / "prueba.json"

def request(port, path, body=None, token=None, expected=200):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(f"https://localhost:{port}{path}", headers=headers,
                                 data=None if body is None else json.dumps(body).encode())
    try:
        with urllib.request.urlopen(req, context=context, timeout=20) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, payload = error.code, error.read()
    if status != expected:
        raise SystemExit(f"Fallo de verificación: {path}, HTTP {status}; se esperaba {expected}.")
    return json.loads(payload) if payload else None

for port, path in [(8441, "/management/health"), (8442, "/actuator/health")]:
    assert request(port, path)["status"] == "UP"
    try:
        urllib.request.urlopen(f"https://localhost:{port}{path}", timeout=5)
    except urllib.error.URLError as error:
        if not isinstance(error.reason, ssl.SSLCertVerificationError):
            raise
    else:
        raise SystemExit("La conexión sin confiar en la CA local no fue rechazada.")
request(8441, "/api/catalogo/estado", expected=401)
request(8442, "/api/account", expected=401)
print("OK: HTTPS con CA validada; sin CA se rechaza; APIs protegidas exigen JWT.")

if fixture.exists():
    account = json.loads(fixture.read_text())
else:
    login = "compose." + secrets.token_hex(5)
    password = secrets.token_urlsafe(24)
    result = request(8442, "/api/register", {"login": login, "password": password,
                    "firstName": "Prueba", "lastName": "Despliegue",
                    "email": login + "@example.com", "langKey": "es"}, expected=201)
    account = {"login": login, "password": password, "publicId": result["publicId"]}
    fixture.write_text(json.dumps(account))
    fixture.chmod(0o600)
token = request(8442, "/api/authenticate", {"username": account["login"],
                "password": account["password"], "rememberMe": False})["id_token"]
assert request(8442, "/api/account", token=token)["publicId"] == account["publicId"]
state = request(8441, "/api/catalogo/estado", token=token)
assert state["inicializado"] and state["version"] is not None and state["ultimoError"] is None
if "version" in account:
    assert state["version"] >= account["version"]
account["version"] = state["version"]
fixture.write_text(json.dumps(account))
print("OK: registro/login y usuario persistido; catálogo inicializado sin último error.")
# Consulta de lectura: obliga a turnos a consumir catálogo por HTTPS y las ocupaciones centrales.
today = datetime.now(ZoneInfo("America/Argentina/Mendoza")).date()
date = next(today + timedelta(days=i) for i in range(1, 8) if (today + timedelta(days=i)).weekday() == 3)
request(8442, f"/api/turnos/disponibilidad?profesionalId=202&fecha={date}", token=token)
print("OK: turnos consulta catálogo por HTTPS y ocupaciones de la cátedra.")
