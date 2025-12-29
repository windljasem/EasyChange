#!/bin/sh

APP_HOME="$(cd "$(dirname "$0")" && pwd)"

JAVA_CMD="java"

exec "$JAVA_CMD" -jar "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
