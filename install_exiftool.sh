#!/usr/bin/env bash
# Install ExifTool on Linux or macOS

set -e

if command -v exiftool &>/dev/null; then
    echo "ExifTool is already installed: $(exiftool -ver)"
    exit 0
fi

OS="$(uname -s)"

if [ "$OS" = "Darwin" ]; then
    if command -v brew &>/dev/null; then
        echo "Installing ExifTool via Homebrew..."
        brew install exiftool
    else
        echo "Homebrew not found. Install it from https://brew.sh, then re-run this script."
        exit 1
    fi
elif [ "$OS" = "Linux" ]; then
    if command -v apt-get &>/dev/null; then
        echo "Installing ExifTool via apt..."
        sudo apt-get update -qq
        sudo apt-get install -y libimage-exiftool-perl
    elif command -v dnf &>/dev/null; then
        echo "Installing ExifTool via dnf..."
        sudo dnf install -y perl-Image-ExifTool
    elif command -v pacman &>/dev/null; then
        echo "Installing ExifTool via pacman..."
        sudo pacman -S --noconfirm perl-image-exiftool
    else
        echo "Could not detect package manager."
        echo "Install ExifTool manually: https://exiftool.org"
        exit 1
    fi
else
    echo "Unsupported OS: $OS"
    echo "Install ExifTool manually: https://exiftool.org"
    exit 1
fi

echo ""
echo "ExifTool installed successfully: $(exiftool -ver)"
echo "You can now run:  python3 app.py"
