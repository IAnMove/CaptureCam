#!/usr/bin/env bash
# Genera el icono (512x512) y la imagen destacada (1024x500) de la ficha de Google Play.
# Necesita: rsvg-convert, ImageMagick (magick) y las capturas de fastlane/metadata/android/en-US/images/phoneScreenshots.
set -euo pipefail
cd "$(dirname "$0")/.."
G=fastlane/metadata/android/en-US/images   # estructura de fastlane supply
SH=$G/phoneScreenshots
BOLD=/usr/share/fonts/noto/NotoSans-Bold.ttf
REG=/usr/share/fonts/noto/NotoSans-Regular.ttf
mkdir -p "$G"

# Icono: el mismo dibujo que el icono adaptativo de la app (cuadrado completo; Play aplica la máscara).
cat > "$G/.icon.svg" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="#1B1B1F"/>
  <circle cx="54" cy="54" r="18" fill="#E53935"/>
  <circle cx="54" cy="54" r="27" fill="none" stroke="#FFFFFF" stroke-width="4"/>
</svg>
SVG
rsvg-convert -w 512 -h 512 "$G/.icon.svg" -o "$G/icon.png"

# Versión redondeada pequeña para la imagen destacada.
sed 's/<rect width="108" height="108"/<rect rx="24" width="108" height="108"/' "$G/.icon.svg" > "$G/.icon_round.svg"
rsvg-convert -w 120 -h 120 "$G/.icon_round.svg" -o "$G/.icon_round.png"

# Móviles con esquinas redondeadas y sombra.
phone() { # origen ancho -> salida
  local src=$1 w=$2 out=$3 h=$(( $2 * 1920 / 1080 ))
  magick "$src" -resize "${w}x${h}!" -alpha set \
    \( -size "${w}x${h}" xc:none -fill white -draw "roundrectangle 0,0,$((w-1)),$((h-1)),26,26" \) \
    -compose DstIn -composite -compose over \
    \( +clone -background black -shadow 55x14+0+10 \) +swap -background none -layers merge +repage "$out"
}
phone "$SH/1_main.png" 250 "$G/.p1.png"
phone "$SH/4_gallery.png" 250 "$G/.p2.png"

magick -size 1024x500 radial-gradient:'#3a2024-#17171b' \
  "$G/.icon_round.png" -geometry +64+150 -composite \
  -font "$BOLD" -pointsize 62 -fill white -annotate +204+222 'CaptureCam' \
  -font "$REG" -pointsize 30 -fill '#D4D4DC' -annotate +66+330 'Record your screen. No ads.' \
  -font "$REG" -pointsize 22 -fill '#9A9AA6' -annotate +66+392 'No accounts  ·  No internet access  ·  Free' \
  "$G/.p2.png" -geometry +805+78 -composite \
  "$G/.p1.png" -geometry +600+44 -composite \
  -alpha off -depth 8 "$G/featureGraphic.png"
rm -f "$G"/.icon.svg "$G"/.icon_round.svg "$G"/.icon_round.png "$G"/.p1.png "$G"/.p2.png
magick identify "$G/icon.png" "$G/featureGraphic.png"
