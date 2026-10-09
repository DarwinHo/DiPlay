"""Import runtime identity only from the hash-verified upstream 0.2.15 APK."""

import argparse
import hashlib
from pathlib import Path
import zipfile


OFFICIAL_SHA256 = "4bf45f16d6b1ab0a61462b831014081f07240f5596c90ca6bf38fb43f9890511"
FILES = ("identity.pk8", "certificate.p7b")


def prepare(apk: Path, output: Path) -> None:
    repository = Path(__file__).resolve().parents[1]
    output = output.resolve()
    if output == repository or repository in output.parents:
        raise SystemExit("Runtime assets must stay outside the source repository")
    if hashlib.sha256(apk.read_bytes()).hexdigest() != OFFICIAL_SHA256:
        raise SystemExit("Official DiPlay 0.2.15 APK SHA-256 mismatch")
    with zipfile.ZipFile(apk) as archive:
        contents = {name: archive.read("assets/offline-mfi/" + name) for name in FILES}
    if not all(contents.values()):
        raise SystemExit("Upstream runtime authentication is incomplete")
    directory = output / "offline-mfi"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    directory.chmod(0o700)
    for name, content in contents.items():
        path = directory / name
        path.write_bytes(content)
        path.chmod(0o600)
    print("Verified upstream APK and prepared two external runtime files")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--official-apk", required=True, type=Path)
    parser.add_argument("--output-directory", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.official_apk, args.output_directory)
