#!/usr/bin/env python3
"""Generate the synthetic PDF used by the isolated upload-to-report system test."""

from __future__ import annotations

import sys
from pathlib import Path

TITLE = "Synthetic upload-to-report fixture study"
DOI = "10.5555/papertrail.system-test.upload-report.2026"
LINES = [
    TITLE,
    "Riley Example and Jordan Researcher",
    f"DOI: {DOI}",
    "",
    "Results",
    "Conservative reference resolution prevents ambiguous citations from being assigned unsupported paper identities.",
    "This synthetic document exists only to exercise the local upload, parsing, indexing, and report workflow.",
    "Its citation and evidence are test fixtures and do not describe a real study or establish model accuracy.",
    "",
    "References",
    f"[1] {TITLE}. Riley Example and Jordan Researcher. 2026. https://doi.org/{DOI}",
]


def pdf_escape(value: str) -> bytes:
    return value.encode("ascii").replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def make_pdf() -> bytes:
    commands = [b"BT", b"/F1 10 Tf", b"48 760 Td", b"14 TL"]
    for line in LINES:
        if line:
            commands.append(b"(" + pdf_escape(line) + b") Tj")
        commands.append(b"T*")
    commands.append(b"ET")
    stream = b"\n".join(commands) + b"\n"
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
        b"<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n" + stream + b"endstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        b"<< /Title (Synthetic upload-to-report fixture study) /Author (Paper T-Rail system-test fixture) >>",
    ]
    result = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, body in enumerate(objects, start=1):
        offsets.append(len(result))
        result.extend(f"{index} 0 obj\n".encode("ascii"))
        result.extend(body)
        result.extend(b"\nendobj\n")
    xref_offset = len(result)
    result.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    result.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    result.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode(
            "ascii"
        )
    )
    return bytes(result)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: generate_issue55_fixture_pdf.py <output.pdf>")
    destination = Path(sys.argv[1])
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(make_pdf())


if __name__ == "__main__":
    main()
