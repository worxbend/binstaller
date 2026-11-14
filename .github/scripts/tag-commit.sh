#!/usr/bin/env bash
# Print the commit that tag <name> points at on origin, or nothing when the tag does not exist.
set -euo pipefail

tag="${1:?usage: tag-commit.sh <tag>}"

# ls-remote matches a pattern against the end of a ref name, so a pattern for this tag would also
# select a branch named "x/refs/tags/<tag>". List every ref and compare the full name instead.
# An annotated tag is listed twice, its own object first and then "<name>^{}" with the commit it
# points at, so the last match is the commit for both kinds of tag.
git ls-remote origin | awk -v ref="refs/tags/${tag}" '
  $2 == ref || $2 == ref "^{}" { sha = $1 }
  END { if (sha != "") print sha }
'
