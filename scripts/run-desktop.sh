#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./mvnw -B -ntp -pl pos-desktop -am install -DskipTests
exec ./mvnw -pl pos-desktop javafx:run
