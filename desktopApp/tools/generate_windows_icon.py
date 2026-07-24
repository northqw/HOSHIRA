from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_DIR = ROOT / "src" / "main" / "resources" / "icons"
PNG_PATH = RESOURCE_DIR / "hoshira.png"
ICO_PATH = RESOURCE_DIR / "hoshira.ico"


def build_icon(size: int = 1024) -> Image.Image:
    image = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    margin = int(size * 0.045)
    radius = int(size * 0.225)
    draw.rounded_rectangle(
        (margin, margin, size - margin, size - margin),
        radius=radius,
        fill=(5, 5, 6, 255),
        outline=(45, 46, 52, 255),
        width=max(2, int(size * 0.012)),
    )

    shield = [
        (size * 0.50, size * 0.16),
        (size * 0.77, size * 0.31),
        (size * 0.77, size * 0.69),
        (size * 0.50, size * 0.84),
        (size * 0.23, size * 0.69),
        (size * 0.23, size * 0.31),
        (size * 0.50, size * 0.16),
    ]
    stroke = max(4, int(size * 0.055))
    draw.line(shield, fill=(244, 244, 247, 255), width=stroke, joint="curve")

    glyph_stroke = max(4, int(size * 0.068))
    draw.line(
        [(size * 0.39, size * 0.34), (size * 0.34, size * 0.67)],
        fill=(255, 255, 255, 255),
        width=glyph_stroke,
    )
    draw.line(
        [(size * 0.66, size * 0.33), (size * 0.61, size * 0.66)],
        fill=(255, 255, 255, 255),
        width=glyph_stroke,
    )
    draw.line(
        [(size * 0.37, size * 0.52), (size * 0.64, size * 0.48)],
        fill=(255, 255, 255, 255),
        width=glyph_stroke,
    )
    return image


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
