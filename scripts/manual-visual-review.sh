#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
echo "Local visual review: $(basename "$repo_root")"
echo
echo "Generated screenshots and visual artifacts:"
find "$repo_root/test-artifacts" "$repo_root/build/run" "$repo_root/run" \
  -type f \( -iname '*.png' -o -iname '*.jpg' \) -print 2>/dev/null | sort || true
echo
echo "Runtime PNG assets:"
find "$repo_root/src/main/resources/assets" -type f -iname '*.png' -print 2>/dev/null | sort \
  | while IFS= read -r asset; do file "$asset"; done
echo
cat <<'CHECKLIST'
Review locally at native scale and integer zoom:
- correct dimensions, transparency, crisp pixel edges, and vanilla material palette;
- readable English and Traditional Chinese text without clipping;
- vanilla spacing, widgets, tooltips, focus, and interaction feedback;
- screenshots match the owning production screen and supported GUI scales;
- Observer views use the production Screen path and contain no framebuffer mirror.

Record approval or rejection locally before publishing.
CHECKLIST
