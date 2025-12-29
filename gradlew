#!/bin/sh
APP_HOME="$(cd "$(dirname "$0")" && pwd)"

exec java \
 -cp "$APP_HOME/gradle/wrapper/gradle-wrapper.jar:$APP_HOME/gradle/wrapper/gradle-wrapper-shared.jar" \
 org.gradle.wrapper.GradleWrapperMain "$@"
