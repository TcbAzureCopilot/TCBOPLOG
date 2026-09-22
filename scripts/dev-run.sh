#!/bin/sh
# Local development server: Jetty 9.4 + H2 (DB2 mode), config/app.dev.properties (auth.mode=dev).
# Usage: scripts/dev-run.sh   → http://localhost:8080/mrlog/
cd "$(dirname "$0")/.." || exit 1
if [ -z "$JAVA_HOME" ]; then
  for c in /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home; do
    [ -d "$c" ] && JAVA_HOME="$c" && export JAVA_HOME && break
  done
fi
[ -n "$JAVA_HOME" ] && PATH="$JAVA_HOME/bin:$PATH"
PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
export PATH
exec mvn -B -q jetty:run
