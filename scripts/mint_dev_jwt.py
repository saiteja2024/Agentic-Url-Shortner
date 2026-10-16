#!/usr/bin/env python3
"""Mints an HS256 JWT for local development (ORCH-038). Standard library only.

Signs with ORCHESTRATOR_JWT_HMAC_SECRET from the environment or .env, the same secret the
orchestrator verifies with. Production deployments use a real identity provider instead
(orchestrator.security.jwt.issuer-uri or jwk-set-uri), and this script is not needed.

  python3 scripts/mint_dev_jwt.py --user alice --scope "runs:write approvals:write"
  curl -H "Authorization: Bearer $(python3 scripts/mint_dev_jwt.py --user alice)" ...
"""

import argparse
import base64
import hashlib
import hmac
import json
import os
import sys
import time
from pathlib import Path


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def secret_from_env_file() -> str | None:
    env = Path(__file__).resolve().parent.parent / ".env"
    if not env.exists():
        return None
    for line in env.read_text().splitlines():
        key, sep, value = line.partition("=")
        if sep and key.strip() == "ORCHESTRATOR_JWT_HMAC_SECRET":
            return value.strip()
    return None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--user", default="dev-user", help="preferred_username, recorded as the decider")
    parser.add_argument("--scope", default="runs:write approvals:write", help="space-separated scopes")
    parser.add_argument("--minutes", type=int, default=60, help="lifetime in minutes")
    args = parser.parse_args()

    secret = os.environ.get("ORCHESTRATOR_JWT_HMAC_SECRET") or secret_from_env_file()
    if not secret or len(secret.encode()) < 32:
        print("Set ORCHESTRATOR_JWT_HMAC_SECRET (at least 32 bytes) in .env or the environment.", file=sys.stderr)
        return 1

    now = int(time.time())
    header = {"alg": "HS256", "typ": "JWT"}
    claims = {"sub": f"dev|{args.user}", "preferred_username": args.user, "scope": args.scope,
              "iat": now, "exp": now + args.minutes * 60}
    signing_input = f"{b64url(json.dumps(header).encode())}.{b64url(json.dumps(claims).encode())}"
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    print(f"{signing_input}.{b64url(signature)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
