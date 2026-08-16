from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_DIR = ROOT / "src" / "main" / "resources" / "icons"
PNG_PATH = RESOURCE_DIR / "hoshira.png"
ICO_PATH = RESOURCE_DIR / "hoshira.ico"


def cubic(p0, p1, p2, p3, steps: int = 32):
    points = []
    for index in range(steps):
        t = index / steps
        inverse = 1.0 - t
        points.append(
            (
                inverse**3 * p0[0]
                + 3 * inverse**2 * t * p1[0]
                + 3 * inverse * t**2 * p2[0]
                + t**3 * p3[0],
                inverse**3 * p0[1]
                + 3 * inverse**2 * t * p1[1]
                + 3 * inverse * t**2 * p2[1]
                + t**3 * p3[1],
            )
        )
    return points


def build_icon(size: int = 1024) -> Image.Image:
    scale = 4
    canvas_size = size * scale
    image = Image.new("RGBA", (canvas_size, canvas_size), (11, 11, 13, 255))
    draw = ImageDraw.Draw(image)

    def point(x: float, y: float):
        return (x / 108 * canvas_size, y / 108 * canvas_size)

    outer = []
    outer += cubic(point(54, 16), point(54, 37), point(71, 54), point(92, 54))
    outer += cubic(point(92, 54), point(71, 54), point(54, 71), point(54, 92))
    outer += cubic(point(54, 92), point(54, 71), point(37, 54), point(16, 54))
    outer += cubic(point(16, 54), point(37, 54), point(54, 37), point(54, 16))
    draw.polygon(outer, fill=(255, 100, 26, 255))

    play = []
    play += cubic(point(50, 43), point(48, 42), point(46, 44), point(46, 47), 12)
    play += cubic(point(46, 47), point(46, 52), point(46, 57), point(46, 61), 12)
    play += cubic(point(46, 61), point(46, 64), point(49, 66), point(52, 64), 12)
    play += cubic(point(52, 64), point(57, 62), point(62, 59), point(67, 57), 12)
    play += cubic(point(67, 57), point(70, 55), point(70, 53), point(67, 51), 12)
    play.append(point(50, 43))
    draw.polygon(play, fill=(11, 11, 13, 255))

    return image.resize((size, size), Image.Resampling.LANCZOS)


def main() -> None:
    RESOURCE_DIR.mkdir(parents=True, exist_ok=True)
    icon = build_icon()
    icon.resize((512, 512), Image.Resampling.LANCZOS).save(PNG_PATH, "PNG")
    icon.save(
        ICO_PATH,
        format="ICO",
        sizes=[
            (16, 16),
            (20, 20),
            (24, 24),
            (32, 32),
            (40, 40),
            (48, 48),
            (64, 64),
            (128, 128),
            (256, 256),
        ],
    )


if __name__ == "__main__":
    main()
