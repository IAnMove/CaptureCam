#!/usr/bin/env python3
"""Valida las traducciones de la app y la ficha de Google Play (textos, capturas e imágenes).

La ficha sigue la estructura de fastlane supply: fastlane/metadata/android/<idioma>/
    title.txt, short_description.txt, full_description.txt, images/{icon,featureGraphic}.png,
    images/phoneScreenshots/*.png
Uso: tools/check_i18n.py [carpeta_de_idioma ...]   (sin argumentos, comprueba todo)
Sale con código 1 si hay algún problema.
"""
import re
import struct
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
STORE = ROOT / "fastlane/metadata/android"
LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
GRAPHICS = {"icon.png": (512, 512), "featureGraphic.png": (1024, 500)}   # tamaños exactos que pide Play
PLACEHOLDER = re.compile(r"%(?:\d\$)?[sd]")


def load(path):
    strings, arrays = {}, {}
    for el in ET.parse(path).getroot():
        if el.get("translatable") == "false":
            continue
        if el.tag == "string":
            strings[el.get("name")] = "".join(el.itertext())
        elif el.tag == "string-array":
            arrays[el.get("name")] = ["".join(i.itertext()) for i in el.findall("item")]
    return strings, arrays


def raw_texts(path):
    """Texto tal como está escrito en el XML (para ver apóstrofos sin escapar)."""
    text = path.read_text(encoding="utf-8")
    return re.findall(r">([^<>]+)</(?:string|item)>", text)


def check_strings(lang_dir, base):
    errors = []
    f = lang_dir / "strings.xml"
    if not f.exists():
        return [f"{lang_dir.name}: falta strings.xml"]
    try:
        strings, arrays = load(f)
    except ET.ParseError as e:
        return [f"{lang_dir.name}: XML inválido: {e}"]
    b_strings, b_arrays = base
    for name in b_strings.keys() - strings.keys():
        errors.append(f"{lang_dir.name}: falta la cadena {name}")
    for name in strings.keys() - b_strings.keys():
        errors.append(f"{lang_dir.name}: cadena sobrante {name}")
    for name, src in b_strings.items():
        tr = strings.get(name)
        if tr is None:
            continue
        if not tr.strip():
            errors.append(f"{lang_dir.name}: {name} está vacía")
        if Counter(PLACEHOLDER.findall(src)) != Counter(PLACEHOLDER.findall(tr)):
            errors.append(f"{lang_dir.name}: {name} cambia los marcadores {PLACEHOLDER.findall(src)} -> {PLACEHOLDER.findall(tr)}")
    for name, src in b_arrays.items():
        tr = arrays.get(name)
        if tr is None:
            errors.append(f"{lang_dir.name}: falta el array {name}")
        elif len(tr) != len(src):
            errors.append(f"{lang_dir.name}: {name} tiene {len(tr)} elementos, se esperaban {len(src)}")
    # aapt exige \' ; un apóstrofo suelto rompe la compilación (salvo texto entre comillas dobles)
    for raw in raw_texts(f):
        s = raw.strip()
        if s.startswith('"') and s.endswith('"'):
            continue
        if re.search(r"(?<!\\)'", s):
            errors.append(f"{lang_dir.name}: apóstrofo sin escapar en «{s[:50]}»")
        if s.startswith(("@", "?")):
            errors.append(f"{lang_dir.name}: empieza por @ o ? sin escapar: «{s[:50]}»")
    return errors


def check_store(lang_dir):
    errors = []
    for fname, limit in LIMITS.items():
        f = lang_dir / fname
        if not f.exists():
            errors.append(f"metadata/android/{lang_dir.name}: falta {fname}")
            continue
        n = len(f.read_text(encoding="utf-8").strip())
        if n == 0:
            errors.append(f"metadata/android/{lang_dir.name}/{fname}: vacío")
        elif n > limit:
            errors.append(f"metadata/android/{lang_dir.name}/{fname}: {n} caracteres (máximo {limit})")
    return errors


def png_size(f):
    return struct.unpack(">II", f.read_bytes()[16:24])


def check_images(lang_dir):
    """Reglas de Google Play: 2 a 8 capturas, lados de 320 a 3840 px y proporción máxima 2:1;
    icono de 512x512 e imagen destacada de 1024x500."""
    errors = []
    images = lang_dir / "images"
    pngs = sorted((images / "phoneScreenshots").glob("*.png"))
    if not 2 <= len(pngs) <= 8:
        errors.append(f"{lang_dir.name}/images/phoneScreenshots: {len(pngs)} capturas (deben ser de 2 a 8)")
    for f in pngs:
        w, h = png_size(f)
        if not (320 <= min(w, h) and max(w, h) <= 3840):
            errors.append(f"{lang_dir.name}/{f.name}: {w}x{h} fuera de 320-3840 px")
        elif max(w, h) > 2 * min(w, h):
            errors.append(f"{lang_dir.name}/{f.name}: {w}x{h} supera la proporción 2:1")
    for name, expected in GRAPHICS.items():
        f = images / name
        if f.exists() and png_size(f) != expected:
            errors.append(f"{lang_dir.name}/images/{name}: {png_size(f)} en vez de {expected}")
    return errors


def main():
    base = load(RES / "values/strings.xml")
    wanted = set(sys.argv[1:])
    errors = []
    for d in sorted(RES.glob("values-*")):
        if (d / "strings.xml").exists() and (not wanted or d.name in wanted):
            errors += check_strings(d, base)
    for d in sorted(p for p in STORE.iterdir() if p.is_dir()):
        if not wanted or d.name in wanted:
            errors += check_store(d)
            errors += check_images(d)
    for e in errors:
        print("ERROR", e)
    print(f"{len(errors)} problemas")
    sys.exit(1 if errors else 0)


if __name__ == "__main__":
    main()
