"""Joins the frame screenshots of a client test into an mp4 with ffmpeg.

    python scripts/test/make-video.py <screenshots folder> <output.mp4> [--name frame] [--fps 10] [--every 1] [--drop-dark]

Frames are the screenshots whose file name contains --name, in the order the test took them (the screenshot
counter prefix). The SpaceGridClientGameTest takes one frame every two game ticks, so ten frames per second
plays at real speed. --every N keeps only every Nth frame, so the video plays N times faster. --drop-dark leaves out frames that are almost entirely black (a frame the renderer missed), and says how many.
"""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("folder", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--name", default="frame")
    parser.add_argument("--fps", type=int, default=10)
    parser.add_argument("--every", type=int, default=1)
    parser.add_argument("--drop-dark", action="store_true")
    args = parser.parse_args()

    ordered = lambda path: int(re.match(r"(\d+)_", path.name).group(1))
    frames = sorted((p for p in args.folder.glob("*.png") if args.name in p.name and re.match(r"\d+_", p.name)), key=ordered)
    if not frames:
        print(f"No screenshots with '{args.name}' in {args.folder}", file=sys.stderr)
        return 1
    if args.drop_dark:
        from PIL import Image, ImageStat

        kept = [p for p in frames if sum(ImageStat.Stat(Image.open(p).convert("L").resize((64, 36))).mean) > 12]
        print(f"dropped {len(frames) - len(kept)} nearly black frames")
        frames = kept
    if args.every > 1:
        frames = frames[:: args.every]
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        print("ffmpeg is not on PATH", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory() as temp:
        for number, frame in enumerate(frames):
            shutil.copyfile(frame, Path(temp) / f"frame{number:05d}.png")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        command = [ffmpeg, "-y", "-loglevel", "error", "-framerate", str(args.fps), "-i", str(Path(temp) / "frame%05d.png"),
                   "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20", str(args.output)]
        subprocess.run(command, check=True)
    print(f"{len(frames)} frames, {len(frames) / args.fps:.1f} s: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
