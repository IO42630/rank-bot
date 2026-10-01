#!/usr/bin/env bash

set -euo pipefail

SKILLS_DIR=".agents/skills"
mkdir -p "$SKILLS_DIR"

echo "Updating agent skills..."

# Helper to run commands quietly and only print if something fails
install_skill() {
  local repo="$1"
  local skill="$2"
  printf "Installing skill '%s'... " "$skill"
  if npx -y skills add "$repo" --skill "$skill" -y > /dev/null 2>&1; then
    echo "done."
  else
    echo "FAILED!"
    return 1
  fi
}

# 1. Install/Update specific skills non-interactively
install_skill "https://github.com/io42630/skills" "plexy-markdown"
install_skill "https://github.com/io42630/skills" "plexy-do-less"

# 2. Keep the root clean: move any generated lockfiles into .agents/
for lockfile in .skills.json skills-lock.json; do
  if [ -f "$lockfile" ]; then
    mv -f "$lockfile" .agents/
  fi
done

echo "Skills updated successfully in $SKILLS_DIR"
