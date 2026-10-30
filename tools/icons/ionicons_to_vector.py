"""Convert Ionicons (MIT, ionic.io/ionicons) SVGs into Android vector drawables.

The launcher uses these iOS-style glyphs in place of the earlier Lucide set
(Apple's SF Symbols can't be used outside Apple platforms). Each mapping keeps
the drawable name the code already uses.

Usage: python3 ionicons_to_vector.py <ionicons/dist/svg dir> <res/drawable dir>
"""
import re
import sys
import xml.etree.ElementTree as ET

MAP = {
    "toggle": "options", "film": "film", "moon": "moon", "sun": "sunny", "settings": "settings",
    "more": "ellipsis-horizontal", "play": "play", "pause": "pause", "wifi": "wifi",
    "volume": "volume-high", "image": "image", "monitor": "desktop", "search": "search",
    "grid": "grid", "sparkle": "sparkles", "clock": "time", "mic": "mic", "arrow_up": "arrow-up",
    "trash": "trash", "cloud": "cloudy", "cloud_sun": "partly-sunny", "cloud_rain": "rainy",
    "cloud_snow": "snow", "cloud_lightning": "thunderstorm", "cloud_fog": "cloudy",
    "calendar": "calendar", "music": "musical-notes", "home": "home", "phone": "phone-portrait",
    "heart": "heart", "map_pin": "location", "frame": "albums", "bulb": "bulb",
}

def num(v):
    return float(re.sub(r"[^0-9.\-]", "", v)) if v else 0.0

def circle(cx, cy, rx, ry):
    return f"M{cx - rx},{cy}a{rx},{ry} 0 1,0 {2 * rx},0a{rx},{ry} 0 1,0 {-2 * rx},0Z"

def to_path(el):
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return a.get("d")
    if tag == "circle":
        r = num(a.get("r"))
        return circle(num(a.get("cx")), num(a.get("cy")), r, r)
    if tag == "ellipse":
        return circle(num(a.get("cx")), num(a.get("cy")), num(a.get("rx")), num(a.get("ry")))
    if tag == "rect":
        x, y, w, h = (num(a.get(k)) for k in ("x", "y", "width", "height"))
        rx = num(a.get("rx") or a.get("ry") or "0"); ry = num(a.get("ry") or a.get("rx") or "0")
        if rx == 0:
            return f"M{x},{y}h{w}v{h}h{-w}Z"
        return (f"M{x + rx},{y}h{w - 2 * rx}a{rx},{ry} 0 0 1 {rx},{ry}v{h - 2 * ry}a{rx},{ry} 0 0 1 {-rx},{ry}"
                f"h{-(w - 2 * rx)}a{rx},{ry} 0 0 1 {-rx},{-ry}v{-(h - 2 * ry)}a{rx},{ry} 0 0 1 {rx},{-ry}Z")
    if tag == "line":
        return f"M{num(a.get('x1'))},{num(a.get('y1'))}L{num(a.get('x2'))},{num(a.get('y2'))}"
    if tag in ("polyline", "polygon"):
        pts = re.findall(r"-?[\d.]+", a.get("points", ""))
        pairs = [f"{pts[i]},{pts[i + 1]}" for i in range(0, len(pts) - 1, 2)]
        return "M" + "L".join(pairs) + ("Z" if tag == "polygon" else "")
    return None

def convert(svg_path, name):
    root = ET.parse(svg_path).getroot()
    vb = [float(v) for v in root.get("viewBox", "0 0 512 512").split()]
    out = []
    for el in root.iter():
        d = to_path(el)
        if not d:
            continue
        a = el.attrib
        fill = a.get("fill", "currentColor")
        stroke = a.get("stroke")
        attrs = [f'android:pathData="{d}"']
        attrs.append('android:fillColor="#FFFFFFFF"' if fill != "none" else 'android:fillColor="#00000000"')
        if stroke and stroke != "none":
            attrs.append('android:strokeColor="#FFFFFFFF"')
            attrs.append(f'android:strokeWidth="{num(a.get("stroke-width", "32"))}"')
            attrs.append(f'android:strokeLineCap="{a.get("stroke-linecap", "butt")}"')
            attrs.append(f'android:strokeLineJoin="{a.get("stroke-linejoin", "miter")}"')
        if a.get("opacity"):
            attrs.append(f'android:fillAlpha="{a.get("opacity")}"')
        out.append("    <path " + " ".join(attrs) + " />")
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Ionicons "{name}" (MIT licence, ionic.io/ionicons), iOS-style glyph. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="{vb[2]}"
    android:viewportHeight="{vb[3]}">
''' + "\n".join(out) + "\n</vector>\n"

if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    for ours, ion in MAP.items():
        with open(f"{dst}/ic_lucide_{ours}.xml", "w") as f:
            f.write(convert(f"{src}/{ion}.svg", ion))
        print("ic_lucide_" + ours, "<-", ion)
