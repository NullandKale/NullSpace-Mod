"""Joins the frame screenshots of a client test into an mp4 with ffmpeg.

    python scripts/test/make-video.py <screenshots folder> <output.mp4> [--name frame] [--fps 10]

Frames are the screenshots whose file name contains --name, in the order the test took them (the screenshot
counter prefix). The SpaceGridClientGameTest takes one frame every two game ticks, so ten frames per second
plays at real speed.
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
    args = parser.parse_args()

    ordered = lambda path: int(re.match(r"(\d+)_", path.name).group(1))
    frames = sorted((p for p in args.folder.glob("*.png") if args.name in p.name and re.match(r"\d+_", p.name)), key=ordered)
    if not frames:
        print(f"No screenshots with '{args.name}' in {args.folder}", file=sys.stderr)
        return 1
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
