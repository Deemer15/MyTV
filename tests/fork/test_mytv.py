"""MyTV fork invariants.

A small suite that checks only this private fork's own front page and assets, not upstream's
contribution rules. Run it instead of the upstream suite:

    .venv\\Scripts\\python.exe -m unittest discover -s tests\\fork -v     (Windows)
    tools/venv/bin/python -m unittest discover -s tests/fork -v           (POSIX)

The upstream suite under tests/ still exists for reference and for verifying the safety scripts, but
it reads upstream's root README.md and so reports failures on this fork by design.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
README = ROOT / "README.md"
UPSTREAM = ROOT / "README.upstream.md"
SCREENSHOTS = ROOT / "docs" / "screenshots"

# The APK size gate, kept in step with .github/workflows/ci.yml.
SIZE_GATE = 786_432
APK = ROOT / "launcher" / "app" / "src" / "main"  # presence check only; the built APK is gitignored
BUILT_APK = ROOT / "launcher" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"

# Images the front page shows, relative to the repo root.
FRONT_PAGE_IMAGES = [
    "docs/screenshots/home.jpg",
    "docs/screenshots/explore.jpg",
    "docs/screenshots/explore-shows.jpg",
    "docs/screenshots/episodes.jpg",
]


class FrontPageTest(unittest.TestCase):
    def test_readme_is_the_mytv_page(self):
        text = README.read_text(encoding="utf-8")
        self.assertTrue(text.lstrip().startswith("# MyTV"),
                        "README.md is no longer the MyTV front page")

    def test_readme_points_at_the_upstream_copy(self):
        text = README.read_text(encoding="utf-8")
        self.assertIn("README.upstream.md", text,
                      "the front page should link to the preserved upstream README")

    def test_upstream_readme_is_kept(self):
        self.assertTrue(UPSTREAM.is_file(),
                        "README.upstream.md is missing; the upstream page should be kept for reference")

    def test_no_stray_mytv_md(self):
        self.assertFalse((ROOT / "MYTV.md").exists(),
                         "MYTV.md should be gone; its content is the README now")


class ScreenshotTest(unittest.TestCase):
    def test_every_front_page_image_exists(self):
        text = README.read_text(encoding="utf-8")
        for rel in FRONT_PAGE_IMAGES:
            with self.subTest(image=rel):
                self.assertIn(rel, text, "the README no longer references this screenshot")
                self.assertTrue((ROOT / rel).is_file(), f"{rel} is missing from the tree")

    def test_no_broken_local_image_links(self):
        # Every docs/screenshots image the README references must exist on disk.
        text = README.read_text(encoding="utf-8")
        referenced = set(re.findall(r"\(docs/screenshots/[^)]+\)", text))
        for ref in referenced:
            rel = ref.strip("()")
            with self.subTest(image=rel):
                self.assertTrue((ROOT / rel).is_file(), f"README references {rel}, which is not in the tree")

    def test_screenshots_are_a_sane_size(self):
        # JPEGs under a megabyte; a stray PNG or raw capture would be far bigger.
        for shot in SCREENSHOTS.glob("*.jpg"):
            with self.subTest(image=shot.name):
                self.assertLess(shot.stat().st_size, 1_000_000,
                                f"{shot.name} is over 1 MB; re-export it as JPEG")


class SizeGateTest(unittest.TestCase):
    def test_gate_matches_ci(self):
        ci = (ROOT / ".github" / "workflows" / "ci.yml").read_text(encoding="utf-8")
        self.assertIn(str(SIZE_GATE), ci,
                      "the size gate in this test no longer matches ci.yml")

    def test_built_apk_under_gate_when_present(self):
        if not BUILT_APK.is_file():
            self.skipTest("no built APK; run the launcher build first")
        size = BUILT_APK.stat().st_size
        self.assertLess(size, SIZE_GATE,
                        f"release APK is {size} B, over the {SIZE_GATE} B gate")


if __name__ == "__main__":
    unittest.main()
