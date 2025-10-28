#!/bin/bash
# Script to trigger build-and-test workflow on all supported OS and Java combinations
# This is designed to be run when a release happens to ensure compatibility across all environments

set -e

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# OS platforms to test on release (latest versions, both macOS architectures, Windows)
OS_PLATFORMS=(
    "ubuntu-latest"
    "macos-latest"      # Apple Silicon (ARM)
    "macos-15-intel"    # Intel
    "windows-latest"
)

# All supported Java versions
JAVA_VERSIONS=(11 17 21)

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Triggering Build and Test Workflows${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Total OS platforms: ${#OS_PLATFORMS[@]}"
echo "Total Java versions: ${#JAVA_VERSIONS[@]}"
echo "Total combinations: $((${#OS_PLATFORMS[@]} * ${#JAVA_VERSIONS[@]}))"
echo ""

# Counter for tracking
total_triggered=0
failed_triggers=0

# Iterate through all combinations
for os in "${OS_PLATFORMS[@]}"; do
    for java in "${JAVA_VERSIONS[@]}"; do
        echo -e "${YELLOW}Triggering:${NC} OS=${os}, Java=${java}"

        # Trigger the workflow using gh CLI
        if gh workflow run build-and-test.yml \
            -f java_versions="[${java}]" \
            -f os="${os}"; then
            echo -e "${GREEN}✓ Successfully triggered${NC}"
            ((total_triggered++))
        else
            echo -e "${RED}✗ Failed to trigger${NC}"
            ((failed_triggers++))
        fi

        # Small delay to avoid rate limiting
        sleep 2
    done
    echo ""
done

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Summary${NC}"
echo -e "${GREEN}========================================${NC}"
echo "Successfully triggered: ${total_triggered}"
echo "Failed triggers: ${failed_triggers}"
echo ""

if [ $failed_triggers -eq 0 ]; then
    echo -e "${GREEN}All workflow runs triggered successfully!${NC}"
    echo ""
    echo "Monitor the runs with:"
    echo "  gh run list --workflow=build-and-test.yml"
    echo ""
    echo "Watch a specific run:"
    echo "  gh run watch <run-id>"
    exit 0
else
    echo -e "${RED}Some workflow runs failed to trigger.${NC}"
    echo "Check your GitHub CLI authentication and permissions."
    exit 1
fi
