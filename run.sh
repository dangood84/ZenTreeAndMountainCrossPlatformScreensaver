#!/bin/bash
# Java 21. Works on the 2014 Catalina MacBook and on newer Macs.
set -euo pipefail
cd "$(dirname "$0")"
javac --release 21 -d out src/zen/*.java
exec java -cp out zen.ZenSaver "$@"
