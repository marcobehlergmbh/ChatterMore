#!/bin/sh

set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
project_dir=$(dirname -- "$script_dir")
signing_dir="$project_dir/.signing"
keystore_file="$signing_dir/chattermore-upload.jks"
properties_file="$signing_dir/keystore.properties"
key_alias="chattermore-upload"

if [ -e "$keystore_file" ] || [ -e "$properties_file" ]; then
    echo "Refusing to overwrite existing signing material in $signing_dir" >&2
    exit 1
fi

command -v keytool >/dev/null 2>&1 || {
    echo "keytool was not found. Install a JDK and try again." >&2
    exit 1
}

command -v openssl >/dev/null 2>&1 || {
    echo "openssl was not found." >&2
    exit 1
}

umask 077
mkdir -p "$signing_dir"
signing_password=$(openssl rand -hex 24)

keytool \
    -genkeypair \
    -keystore "$keystore_file" \
    -storetype PKCS12 \
    -storepass "$signing_password" \
    -keypass "$signing_password" \
    -alias "$key_alias" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity 10000 \
    -dname "CN=ChatterMore, OU=Mobile, O=Marco Behler, L=Berlin, ST=Berlin, C=DE"

{
    printf 'storeFile=.signing/chattermore-upload.jks\n'
    printf 'storePassword=%s\n' "$signing_password"
    printf 'keyAlias=%s\n' "$key_alias"
    printf 'keyPassword=%s\n' "$signing_password"
} > "$properties_file"

echo "Created ChatterMore upload signing material in $signing_dir"
echo "Back up both files securely. They are excluded from Git."
