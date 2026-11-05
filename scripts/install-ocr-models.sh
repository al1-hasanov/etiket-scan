#!/bin/sh
set -eu

destination=${1:?Pass a destination directory for the Tesseract models}
revision=e12c65a915945e4c28e237a9b52bc4a8f39a0cec
base=https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/$revision
mkdir -p "$destination"

install_model() {
    name=$1
    checksum=$2
    file="$destination/$name.traineddata"
    if [ -f "$file" ] && printf '%s  %s\n' "$checksum" "$file" | sha256sum -c --status; then
        return
    fi
    temp=$(mktemp "$destination/.traineddata.XXXXXX")
    trap 'rm -f "$temp"' EXIT
    curl --fail --silent --show-error --location --retry 3 --max-time 180 "$base/$name.traineddata" --output "$temp"
    printf '%s  %s\n' "$checksum" "$temp" | sha256sum -c --status
    chmod 644 "$temp"
    mv "$temp" "$file"
    trap - EXIT
}

install_model eng 8280aed0782fe27257a68ea10fe7ef324ca0f8d85bd2fd145d1c2b560bcb66ba
install_model deu 8407331d6aa0229dc927685c01a7938fc5a641d1a9524f74838cdac599f0d06e
install_model aze 6f183760f210177cf8c8eb6bf4821a8e488a4137ad06549ae483266c7cfd455f
install_model tur e0c3338dc17503dc7d335a507c9ae01b2b46cfd07561171e1e1ac55d85e8e438
install_model rus b617eb6830ffabaaa795dd87ea7fd251adfe9cf0efe05eb9a2e8128b7728d6b6
